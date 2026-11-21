package org.simplepoint.plugin.dna.runtime.service.record;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordRow;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilter;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordLookupQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSort;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSortDirection;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.springframework.stereotype.Component;

/**
 * Transaction-local aggregate input assembly and calculation. Public intent is validated before
 * entering this boundary. Internal complete reads preserve tenant/row scope and never escape as
 * responses; only computed outputs augment the caller's legal write patch.
 */
@Component
public class RecordCalculationWriteSupport {
  private final RuntimeRecordModelResolver models;
  private final PhysicalSchemaCodec schemas;
  private final RecordSqlCompiler sql;

  /** Uses trusted model resolution and the existing Record SQL compiler. */
  public RecordCalculationWriteSupport(
      final RuntimeRecordModelResolver models,
      final PhysicalSchemaCodec schemas,
      final RecordSqlCompiler sql) {
    this.models = models;
    this.schemas = schemas;
    this.sql = sql;
  }

  Binding bind(
      final RuntimeActor actor,
      final RuntimeRecordModel requested,
      final RecordCalculationStore.Installed installed) {
    if (installed == null || installed.policy().definition().isEmpty()) {
      return null;
    }
    var policy = installed.policy();
    var parent = models.resolve(actor, requested.physicalModelId(), policy.parentSchema());
    if (!parent.appId().equals(policy.appId())
        || !parent.formId().equals(policy.formId())
        || !parent.target().equals(requested.target())
        || !parent.activeSchema().equals(requested.activeSchema())
        || parent.requestedVersion() != policy.parentSchema().version()
        || !schemas
            .encode(parent.requestedSchema())
            .checksum()
            .equals(policy.parentSchema().checksum())) {
      throw invalid("Calculation owner or schema does not match the resolved model");
    }
    Map<String, RuntimeRecordModel> details = new LinkedHashMap<>();
    policy
        .definition()
        .details()
        .forEach(
            (scope, definition) ->
                details.put(
                    scope,
                    models.resolve(
                        actor, definition.physicalModelId(), definition.schemaReference())));
    // Compile even for deletes/retries: a corrupt persisted graph is never treated as no policy.
    var evaluator =
        new RecordCalculationEvaluator(
            new RecordCalculationCompiler(schemas, Clock.systemUTC()),
            parent,
            policy.definition(),
            details);
    return new Binding(
        installed, parent, Map.copyOf(details), evaluator.dependencies(), evaluator.references());
  }

  PreparedAggregate prepare(
      final Binding binding,
      final Scope parent,
      final Map<String, Scope> children,
      final OwnedRecordCommand command,
      final String parentId,
      final RecordOperationIdentity operation,
      final Function<RecordSelectPlan, CalciteQueryResult> reader,
      final Instant at) {
    return prepareAggregate(
        binding,
        parent,
        children,
        command.parent().mutations().getFirst(),
        command.details(),
        parentId,
        operation.id(),
        reader,
        at,
        null);
  }

  /**
   * A single-record transaction changes the parent and retains/recalculates its owned collections.
   */
  PreparedAggregate prepareSingle(
      final Binding binding,
      final Scope parent,
      final Map<String, Scope> children,
      final RecordMutation mutation,
      final String parentId,
      final Function<RecordSelectPlan, CalciteQueryResult> reader,
      final Instant at) {
    return prepareAggregate(
        binding, parent, children, mutation, List.of(), parentId, parentId, reader, at, null);
  }

  /** Confirmed mapping inputs use the same aggregate and formula write path. */
  PreparedAggregate prepareMapped(
      Binding binding,
      Scope parent,
      Map<String, Scope> children,
      OwnedRecordCommand command,
      String parentId,
      RecordOperationIdentity operation,
      Function<RecordSelectPlan, CalciteQueryResult> reader,
      Instant at,
      RecordReferenceMappingWrite mappings) {
    return prepareAggregate(
        binding,
        parent,
        children,
        command.parent().mutations().getFirst(),
        command.details(),
        parentId,
        operation.id(),
        reader,
        at,
        Objects.requireNonNull(mappings));
  }

  RecordSequenceLockPlan discoverSequence(
      final Scope parent,
      final Map<String, Scope> children,
      final List<RecordSequenceLockPlan.Intent> intents,
      final Function<RecordSelectPlan, CalciteQueryResult> reader) {
    return RecordSequenceLockDiscovery.discover(
        parent, children, intents, (scope, id, parentRow) -> read(scope, id, parentRow, reader));
  }

  private PreparedAggregate prepareAggregate(
      final Binding binding,
      final Scope parent,
      final Map<String, Scope> children,
      final RecordMutation mutation,
      final List<org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordSet> changes,
      final String parentId,
      final String operationId,
      final Function<RecordSelectPlan, CalciteQueryResult> reader,
      final Instant at,
      final RecordReferenceMappingWrite mappings) {
    var aggregate =
        assemble(
            binding,
            parent,
            children,
            mutation,
            changes,
            parentId,
            operationId,
            reader,
            at,
            mappings);
    var statements = statements(aggregate, parent, children, mutation, parentId, at);
    var checks = referenceChecks(binding, parent, aggregate, mutation, parentId);
    var copies =
        binding == null || mutation instanceof RecordDeleteMutation
            ? List.<RecordReferenceCopyMutation>of()
            : copies(binding, parent, aggregate, parentId, statements, reader);
    return new PreparedAggregate(statements, checks, copies);
  }

  private static List<RecordReferenceCheck> referenceChecks(
      Binding binding,
      Scope parent,
      Aggregate aggregate,
      RecordMutation mutation,
      String parentId) {
    List<RecordReferenceCheck> checks = new ArrayList<>();
    if (binding != null && !(mutation instanceof RecordDeleteMutation)) {
      var finalParent = new LinkedHashMap<>(aggregate.parentInputs());
      finalParent.putAll(aggregate.parentOutputs());
      checks.addAll(
          binding
              .references()
              .checks(
                  "parent",
                  parentId,
                  aggregate.stored() == null ? null : aggregate.stored().values(),
                  finalParent,
                  finalParent,
                  aggregate
                      .mapping()
                      .observations()
                      .getOrDefault(
                          List.of(parent.model().physicalModelId(), parentId), Map.of())));
      binding
          .details()
          .forEach(
              (scope, model) -> {
                var rows = aggregate.rows().get(model.physicalModelId());
                var outputs = aggregate.detailOutputs().get(model.physicalModelId());
                for (int index = 0; index < rows.size(); index++) {
                  var row = rows.get(index);
                  var values = new LinkedHashMap<>(row.inputs());
                  if (outputs != null) {
                    values.putAll(outputs.get(index));
                  }
                  checks.addAll(
                      binding
                          .references()
                          .checks(
                              scope,
                              row.id(),
                              row.old() == null ? null : row.old().values(),
                              values,
                              finalParent,
                              aggregate
                                  .mapping()
                                  .observations()
                                  .getOrDefault(
                                      List.of(model.physicalModelId(), row.id()), Map.of())));
                }
              });
    }
    return List.copyOf(checks);
  }

  private static List<RecordReferenceCopyMutation> copies(
      Binding binding,
      Scope parent,
      Aggregate aggregate,
      String parentId,
      List<RecordSqlStatement> statements,
      Function<RecordSelectPlan, CalciteQueryResult> reader) {
    if (binding.installed().policy().queryProtection().isEmpty()
        && aggregate.mapping().effects().isEmpty()) {
      return List.of();
    }
    var prospective = prospective(parent, aggregate, parentId, statements);
    return RecordDerivedCopyWrite.capture(
            binding,
            parent.model(),
            binding.installed(),
            parent.access().actor().tenantId(),
            prospective.before(),
            prospective.after(),
            statements,
            reader,
            aggregate.mapping().effects())
        .mutations();
  }

  private static Prospective prospective(
      Scope parent, Aggregate aggregate, String parentId, List<RecordSqlStatement> statements) {
    Map<List<String>, Long> versions = new LinkedHashMap<>();
    statements.forEach(
        statement ->
            versions.put(
                List.of(statement.subject().modelId(), statement.recordId()),
                statement.resultVersion()));
    Map<String, List<RecordView>> before = new LinkedHashMap<>();
    before.put(
        parent.model().physicalModelId(),
        aggregate.stored() == null ? List.of() : List.of(aggregate.stored()));
    Map<String, List<RecordView>> after = new LinkedHashMap<>();
    aggregate
        .rows()
        .forEach(
            (model, rows) -> {
              before.put(model, rows.stream().map(Row::old).filter(Objects::nonNull).toList());
              List<RecordView> projected = new ArrayList<>();
              for (int index = 0; index < rows.size(); index++) {
                var row = rows.get(index);
                var values = new LinkedHashMap<>(row.inputs());
                var outputs = aggregate.detailOutputs().get(model);
                if (outputs != null) {
                  values.putAll(outputs.get(index));
                }
                projected.add(
                    new RecordView(
                        row.id(),
                        versions.getOrDefault(
                            List.of(model, row.id()), row.old() == null ? 0 : row.old().version()),
                        values));
              }
              after.put(model, List.copyOf(projected));
            });
    var values = new LinkedHashMap<>(aggregate.parentInputs());
    values.putAll(aggregate.parentOutputs());
    var view =
        new org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView(
            new RecordView(parentId, statements.getFirst().resultVersion(), values), after);
    return new Prospective(Map.copyOf(before), view);
  }

  private Aggregate assemble(
      final Binding binding,
      final Scope parent,
      final Map<String, Scope> children,
      final RecordMutation mutation,
      final List<org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordSet> changes,
      final String parentId,
      final String operationId,
      final Function<RecordSelectPlan, CalciteQueryResult> reader,
      final Instant at,
      final RecordReferenceMappingWrite mappings) {
    for (var expected :
        binding == null ? List.<RuntimeRecordModel>of() : binding.details().values()) {
      var actual = children.get(expected.physicalModelId());
      if (actual == null
          || !actual.model().target().equals(expected.target())
          || !actual.model().activeSchema().equals(expected.activeSchema())) {
        throw invalid("Calculation input model changed during preparation");
      }
    }
    boolean create = mutation instanceof RecordCreateMutation;
    boolean delete = mutation instanceof RecordDeleteMutation;
    RecordView stored = create ? null : one(parent, parentId, reader);
    if (!create && stored.version() != version(mutation)) {
      throw conflict();
    }
    var requested = new LinkedHashMap<String, List<OwnedRecordRow>>();
    changes.forEach(set -> requested.put(set.model().physicalModelId(), set.rows()));
    Map<String, List<Row>> rows = new LinkedHashMap<>();
    Map<String, List<RecordView>> removed = new LinkedHashMap<>();
    int total = 0;
    for (var entry : children.entrySet()) {
      var scope = entry.getValue();
      var old = create ? List.<RecordView>of() : many(scope, parentId, reader);
      var intent = requested.get(entry.getKey());
      if (delete && intent != null && !intent.isEmpty()) {
        throw invalid("Delete cannot introduce detail rows");
      }
      // Omitted collections retain their rows but still recompute when a parent input changes.
      var desired =
          delete
              ? List.<OwnedRecordRow>of()
              : intent != null
                  ? intent
                  : old.stream()
                      .map(row -> new OwnedRecordRow(row.id(), row.version(), Map.of()))
                      .toList();
      Map<String, RecordView> byId = new LinkedHashMap<>();
      old.forEach(row -> byId.put(row.id(), row));
      List<Row> assembled = new ArrayList<>();
      for (int index = 0; index < desired.size(); index++) {
        var row = desired.get(index);
        var prior = row.id() == null ? null : byId.remove(row.id());
        if (row.id() != null && (prior == null || prior.version() != row.expectedVersion())) {
          throw conflict();
        }
        var patch = physical(scope.model(), row.values());
        patch.put(parentColumn(scope.model()), parentId);
        patch.put("row_order", index);
        String id =
            row.id() == null
                ? RecordRequestFingerprint.deterministicRecordId(
                    parent.access().actor().tenantId(),
                    scope.model().physicalModelId(),
                    operationId + ":" + index)
                : row.id();
        assembled.add(
            new Row(id, prior, patch, merge(scope.model(), prior, patch), intent != null));
      }
      total += assembled.size();
      rows.put(entry.getKey(), assembled);
      removed.put(entry.getKey(), List.copyOf(byId.values()));
    }
    if (total > 200) {
      throw invalid("Calculation aggregate exceeds its row budget");
    }
    var patch = physical(parent.model(), values(mutation));
    var parentInputs = delete ? Map.<String, Object>of() : merge(parent.model(), stored, patch);
    var mapped = RecordReferenceMappingWrite.Applied.empty();
    if (mappings != null && !delete) {
      List<RecordReferenceMappingWrite.Input> inputs = new ArrayList<>();
      inputs.add(
          new RecordReferenceMappingWrite.Input(
              parent.model(), parentId, null, stored, patch, parentInputs));
      for (var entry : rows.entrySet()) {
        for (int index = 0; index < entry.getValue().size(); index++) {
          var row = entry.getValue().get(index);
          inputs.add(
              new RecordReferenceMappingWrite.Input(
                  children.get(entry.getKey()).model(),
                  row.id(),
                  row.requested() ? index : null,
                  row.old(),
                  row.patch(),
                  row.inputs()));
        }
      }
      mapped = mappings.apply(binding, inputs, reader);
      var parentPatch =
          mapped
              .patches()
              .getOrDefault(List.of(parent.model().physicalModelId(), parentId), Map.of());
      patch.putAll(parentPatch);
      parentInputs.putAll(parentPatch);
      for (var entry : rows.entrySet()) {
        List<Row> enriched = new ArrayList<>();
        for (var row : entry.getValue()) {
          var extra = mapped.patches().getOrDefault(List.of(entry.getKey(), row.id()), Map.of());
          var rowPatch = new LinkedHashMap<>(row.patch());
          var rowInputs = new LinkedHashMap<>(row.inputs());
          rowPatch.putAll(extra);
          rowInputs.putAll(extra);
          enriched.add(new Row(row.id(), row.old(), rowPatch, rowInputs, row.requested()));
        }
        entry.setValue(enriched);
      }
    }
    Map<String, Object> parentOutputs = Map.of();
    Map<String, List<Map<String, Object>>> detailOutputs = new LinkedHashMap<>();
    if (!delete && binding != null) {
      Map<String, List<Map<String, Object>>> detailInputs = new LinkedHashMap<>();
      binding
          .details()
          .forEach(
              (scope, model) ->
                  detailInputs.put(
                      scope, rows.get(model.physicalModelId()).stream().map(Row::inputs).toList()));
      var evaluator =
          new RecordCalculationEvaluator(
              new RecordCalculationCompiler(schemas, Clock.fixed(at, ZoneOffset.UTC)),
              binding.parent(),
              binding.installed().policy().definition(),
              binding.details());
      evaluator.validateTransition(stored == null ? null : stored.values(), parentInputs);
      evaluator.validateWrite("parent", patch, stored == null ? null : stored.values());
      binding
          .details()
          .forEach(
              (scope, model) ->
                  rows.get(model.physicalModelId())
                      .forEach(
                          row ->
                              evaluator.validateWrite(
                                  scope, row.patch(), row.old() == null ? null : stored.values())));
      var computed = evaluator.evaluate(parentInputs, detailInputs);
      parentOutputs = computed.parent();
      computed
          .details()
          .forEach(
              (scope, output) ->
                  detailOutputs.put(binding.details().get(scope).physicalModelId(), output));
    }
    return new Aggregate(
        stored, parentInputs, patch, parentOutputs, rows, removed, detailOutputs, mapped);
  }

  private List<RecordSqlStatement> statements(
      final Aggregate aggregate,
      final Scope parent,
      final Map<String, Scope> children,
      final RecordMutation mutation,
      final String parentId,
      final Instant at) {
    boolean create = mutation instanceof RecordCreateMutation;
    List<RecordSqlStatement> statements = new ArrayList<>();
    var parentValues = new LinkedHashMap<>(create ? aggregate.parentInputs() : aggregate.patch());
    parentValues.putAll(aggregate.parentOutputs());
    statements.add(statement(parent, mutation, parentValues, parentId, at));
    for (var entry : children.entrySet()) {
      var scope = entry.getValue();
      var collection = aggregate.rows().get(entry.getKey());
      var outputs = aggregate.detailOutputs().get(entry.getKey());
      for (int index = 0; index < collection.size(); index++) {
        var row = collection.get(index);
        var output = outputs == null ? Map.<String, Object>of() : outputs.get(index);
        if (!row.requested() && output.isEmpty()) {
          continue;
        }
        var updated = new LinkedHashMap<>(row.old() == null ? row.inputs() : row.patch());
        updated.putAll(output);
        var change =
            row.old() == null
                ? new RecordCreateMutation("child", Map.of())
                : new RecordUpdateMutation(
                    "child", row.id(), row.old().version(), Map.of("row_order", index));
        statements.add(statement(scope, change, updated, row.id(), at));
      }
      for (var removedRow : aggregate.removed().get(entry.getKey())) {
        statements.add(
            statement(
                scope,
                new RecordDeleteMutation("child", removedRow.id(), removedRow.version()),
                Map.of(),
                removedRow.id(),
                at));
      }
    }
    return List.copyOf(statements);
  }

  /** Uses the same assembly, evaluator and physical type checks without executing a mutation. */
  RecordReferenceCopyRead preview(
      final Binding binding,
      final Scope parent,
      final Map<String, Scope> children,
      final OwnedRecordCommand command,
      final String parentId,
      final RecordOperationIdentity operation,
      final Function<RecordSelectPlan, CalciteQueryResult> reader,
      final Instant at,
      final RecordCalculationAccess.Rules rules,
      final RecordCalculationStore.Installed installed) {
    return previewMapped(
        binding, parent, children, command, parentId, operation, reader, at, rules, installed,
        null);
  }

  RecordReferenceCopyRead previewMapped(
      final Binding binding,
      final Scope parent,
      final Map<String, Scope> children,
      final OwnedRecordCommand command,
      final String parentId,
      final RecordOperationIdentity operation,
      final Function<RecordSelectPlan, CalciteQueryResult> reader,
      final Instant at,
      final RecordCalculationAccess.Rules rules,
      final RecordCalculationStore.Installed installed,
      final RecordReferenceMappingWrite mappings) {
    return previewMapped(
        binding, parent, children, command, parentId, operation, reader, at, rules, installed,
        mappings, null);
  }

  RecordReferenceCopyRead previewMapped(
      final Binding binding,
      final Scope parent,
      final Map<String, Scope> children,
      final OwnedRecordCommand command,
      final String parentId,
      final RecordOperationIdentity operation,
      final Function<RecordSelectPlan, CalciteQueryResult> reader,
      final Instant at,
      final RecordCalculationAccess.Rules rules,
      final RecordCalculationStore.Installed installed,
      final RecordReferenceMappingWrite mappings,
      final RecordRelatedWritePlan related) {
    var mutation = command.parent().mutations().getFirst();
    var aggregate =
        assemble(
            binding,
            parent,
            children,
            mutation,
            command.details(),
            parentId,
            operation.id(),
            reader,
            at,
            mappings);
    var checks = referenceChecks(binding, parent, aggregate, mutation, parentId);
    var statements = statements(aggregate, parent, children, mutation, parentId, at);
    if (related != null) {
      related.verify(
          parent.model(),
          parent.access(),
          command,
          parentId,
          new RecordWritePreparation(statements, null, 1, checks),
          children,
          sql,
          reader);
    }
    var prospective = prospective(parent, aggregate, parentId, statements);
    var proof =
        RecordDerivedCopyWrite.capture(
            binding,
            parent.model(),
            installed,
            parent.access().actor().tenantId(),
            prospective.before(),
            prospective.after(),
            statements,
            reader,
            aggregate.mapping().effects());
    // Independent child writes are forbidden. Recheck after all value and proof reads.
    if (aggregate.stored() != null
        && one(parent, parentId, reader).version() != aggregate.stored().version()) {
      throw conflict();
    }
    var source = prospective.after();
    Map<String, List<RecordView>> details = new LinkedHashMap<>();
    children.forEach(
        (model, scope) ->
            details.put(
                model,
                source.details().get(model).stream()
                    .map(
                        row ->
                            new RecordView(
                                row.id(), row.version(), project(scope, row.values(), rules)))
                    .toList()));
    var projected =
        new OwnedRecordView(
            new RecordView(
                source.parent().id(),
                source.parent().version(),
                project(parent, source.parent().values(), rules)),
            details);
    return new RecordReferenceCopyRead(
        projected,
        proof.origins(),
        new RecordReferenceCopyProjection(
            parent.model(), children.values().stream().map(Scope::model).toList(), Map.of()),
        parent.model(),
        rules.apply(parent.model(), parent.access()));
  }

  private static Map<String, Object> project(
      final Scope scope,
      final Map<String, Object> values,
      final RecordCalculationAccess.Rules rules) {
    var model = scope.model();
    var access = rules.apply(model, scope.access());
    Map<String, Object> projected = new LinkedHashMap<>();
    for (var field : model.requestedSchema().fields()) {
      var owner = model.requestedSchema().owner();
      if (!access.readableFields().contains(field.code())
          || owner != null
              && (field.fieldId().equals(owner.parentFieldId())
                  || field.code().equals("row_order"))) {
        continue;
      }
      Object value = values.get(model.activeCode(field.code()));
      projected.put(
          field.code(),
          value != null && access.maskedFields().contains(field.code())
              ? "***"
              : RecordWireValues.wireValue(model, field.code(), value));
    }
    return projected;
  }

  private RecordSqlStatement statement(
      final Scope scope,
      final RecordMutation mutation,
      final Map<String, Object> values,
      final String id,
      final Instant at) {
    var active = active(scope.model());
    return sql.computedMutation(
        active,
        internal(scope),
        mutation,
        values,
        at,
        mutation instanceof RecordCreateMutation ? id : null);
  }

  private RecordView one(
      final Scope scope,
      final String id,
      final Function<RecordSelectPlan, CalciteQueryResult> reader) {
    var rows = read(scope, id, true, reader);
    if (rows.isEmpty()) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.RECORD_NOT_FOUND, "Record was not found in the authorized scope");
    }
    return rows.getFirst();
  }

  private List<RecordView> many(
      final Scope scope,
      final String parentId,
      final Function<RecordSelectPlan, CalciteQueryResult> reader) {
    return read(scope, parentId, false, reader);
  }

  private List<RecordView> read(
      final Scope scope,
      final String id,
      final boolean parent,
      final Function<RecordSelectPlan, CalciteQueryResult> reader) {
    var model = active(scope.model());
    var access = internal(scope);
    var fields = model.requestedFieldCodes();
    var reference =
        new RecordSchemaReference(
            model.activeVersion(), schemas.encode(model.activeSchema()).checksum());
    Map<String, RecordView> combined = new LinkedHashMap<>();
    // Chunk projections only, never business rows. Every chunk uses the borrowed transaction.
    for (int offset = 0; offset < Math.max(1, fields.size()); offset += 90) {
      var selected =
          fields.isEmpty()
              ? List.of("id")
              : fields.subList(offset, Math.min(fields.size(), offset + 90));
      var plan =
          parent
              ? sql.lookup(
                  model,
                  access,
                  new RecordLookupQuery(model.physicalModelId(), reference, id, selected))
              : sql.page(
                  model,
                  access,
                  new RecordPageQuery(
                      model.physicalModelId(),
                      reference,
                      0,
                      41,
                      selected,
                      new RecordFilter(
                          RecordFilterOperator.EQUALS, parentColumn(model), List.of(id), List.of()),
                      List.of(new RecordSort("row_order", RecordSortDirection.ASC))));
      var rows = RecordQueryServiceImpl.views(reader.apply(plan), plan, model, access);
      if (rows.size() > 40) {
        throw invalid("Stored detail row budget exceeded");
      }
      if (offset > 0
          && !new ArrayList<>(combined.keySet())
              .equals(rows.stream().map(RecordView::id).toList())) {
        throw conflict();
      }
      for (var row : rows) {
        var previous = combined.get(row.id());
        var merged = new LinkedHashMap<>(row.values());
        if (previous != null) {
          if (previous.version() != row.version()) {
            throw conflict();
          }
          merged.putAll(previous.values());
        }
        combined.put(row.id(), new RecordView(row.id(), row.version(), merged));
      }
    }
    return List.copyOf(combined.values());
  }

  private static RuntimeRecordModel active(final RuntimeRecordModel model) {
    return new RuntimeRecordModel(
        model.physicalModelId(),
        model.appId(),
        model.formId(),
        model.activeVersion(),
        model.activeSchema(),
        model.target());
  }

  private static RecordAccessContext internal(final Scope scope) {
    var fields =
        new LinkedHashSet<>(
            scope.model().activeSchema().fields().stream()
                .map(org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition::code)
                .toList());
    fields.addAll(org.simplepoint.plugin.dna.runtime.api.record.RecordSystemField.storageCodes());
    var access = scope.access();
    return new RecordAccessContext(
        access.actor(),
        access.resourceCode(),
        fields,
        fields,
        Set.of(),
        access.rowScope(),
        access.organizationId(),
        access.resourceGeneration(),
        access.queryProtectedFields());
  }

  static Map<String, Object> physical(
      final RuntimeRecordModel model, final Map<String, Object> request) {
    Map<String, Object> values = new LinkedHashMap<>();
    request.forEach(
        (code, value) -> {
          if (model.requestedField(code) == null) {
            throw invalid("Calculation intent contains an unknown or system field");
          }
          values.put(model.activeCode(code), value);
        });
    return values;
  }

  private static Map<String, Object> merge(
      final RuntimeRecordModel model, final RecordView prior, final Map<String, Object> patch) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (var field : model.activeSchema().fields()) {
      Object value =
          prior != null
              ? prior.values().get(field.code())
              : org.simplepoint.plugin.dna.runtime.service.support.LogicalValueInputs.fromCanonical(
                  field.defaultValue());
      values.put(field.code(), patch.containsKey(field.code()) ? patch.get(field.code()) : value);
    }
    return values;
  }

  private static String parentColumn(final RuntimeRecordModel model) {
    return model.activeSchema().fields().stream()
        .filter(field -> field.fieldId().equals(model.activeSchema().owner().parentFieldId()))
        .findFirst()
        .orElseThrow(() -> invalid("Owned calculation parent field is absent"))
        .code();
  }

  private static Map<String, Object> values(final RecordMutation mutation) {
    return mutation instanceof RecordCreateMutation create
        ? create.values()
        : mutation instanceof RecordUpdateMutation update ? update.values() : Map.of();
  }

  private static long version(final RecordMutation mutation) {
    return mutation instanceof RecordUpdateMutation update
        ? update.expectedVersion()
        : ((RecordDeleteMutation) mutation).expectedVersion();
  }

  private static DnaRuntimeException invalid(final String message) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_COMMAND_INVALID, message);
  }

  private static DnaRuntimeException conflict() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_VERSION_CONFLICT,
        "Calculation aggregate changed during write preparation");
  }

  private record Prospective(Map<String, List<RecordView>> before, OwnedRecordView after) {}

  private record Aggregate(
      RecordView stored,
      Map<String, Object> parentInputs,
      Map<String, Object> patch,
      Map<String, Object> parentOutputs,
      Map<String, List<Row>> rows,
      Map<String, List<RecordView>> removed,
      Map<String, List<Map<String, Object>>> detailOutputs,
      RecordReferenceMappingWrite.Applied mapping) {}

  private record Row(
      String id,
      RecordView old,
      Map<String, Object> patch,
      Map<String, Object> inputs,
      boolean requested) {}

  record Scope(RuntimeRecordModel model, RecordAccessContext access) {}

  record Binding(
      RecordCalculationStore.Installed installed,
      RuntimeRecordModel parent,
      Map<String, RuntimeRecordModel> details,
      Map<String, Set<String>> dependencies,
      RecordReferenceRules references) {
    Set<String> controlledParentFields() {
      var controlled = new LinkedHashSet<>(outputs(parent.physicalModelId()));
      installed.policy().definition().referencesOrEmpty().stream()
          .filter(org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceRule::retired)
          .map(rule -> rule.source().fieldId())
          .forEach(controlled::add);
      return Set.copyOf(controlled);
    }

    Set<String> outputs(final String modelId) {
      var definition = installed.policy().definition();
      var outputs = new LinkedHashSet<String>();
      if (parent.physicalModelId().equals(modelId)) {
        outputs.addAll(definition.formulas().keySet());
      } else {
        definition.details().values().stream()
            .filter(scope -> scope.physicalModelId().equals(modelId))
            .findFirst()
            .ifPresent(scope -> outputs.addAll(scope.formulas().keySet()));
      }
      for (var rule : definition.referencesOrEmpty()) {
        String owner =
            rule.source().scope().equals("parent")
                ? parent.physicalModelId()
                : definition.details().get(rule.source().scope()).physicalModelId();
        if (owner.equals(modelId)) {
          rule.mappings().stream()
              .filter(
                  mapping ->
                      mapping.mode()
                          == org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMapping
                              .Mode.SERVER_SNAPSHOT)
              .forEach(mapping -> outputs.add(mapping.destinationFieldId()));
        }
      }
      return Set.copyOf(outputs);
    }

    void verify(final RecordCalculationStore.Installed actual) {
      if (!Objects.equals(installed, actual)) {
        throw invalid("Calculation installation changed before transaction admission");
      }
    }
  }

  record PreparedAggregate(
      List<RecordSqlStatement> statements,
      List<RecordReferenceCheck> references,
      List<RecordReferenceCopyMutation> copies) {
    PreparedAggregate {
      statements = List.copyOf(statements);
      references = List.copyOf(references);
      copies = List.copyOf(copies);
    }
  }
}
