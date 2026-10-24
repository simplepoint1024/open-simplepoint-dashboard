package org.simplepoint.plugin.dna.runtime.service.record;

import java.time.Instant;
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
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordModel;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordPreview;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordQuery;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordSet;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilter;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordLookupQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSort;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSortDirection;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;
import org.springframework.stereotype.Service;

/**
 * Parent-authorized, bounded aggregate CRUD. Child tables have no independent public write path.
 */
@Service
public class OwnedRecordService {
  private final RuntimeRequestContext context;
  private final RuntimeRecordModelResolver models;
  private final RuntimeRecordAccessPolicy policies;
  private final RecordSqlCompiler compiler;
  private final RuntimeCalciteRecordBackend backend;
  private final RecordRequestFingerprint fingerprints;
  private final RecordAdmissionController admission;
  private final RecordAuditSupport audit;
  private final RecordExecutionObserver observer;
  private final RecordCalculationWriteSupport calculations;
  private final RecordCalculationAccess calculationAccess;

  /**
   * Creates the owned-record boundary with explicit authorization and transaction collaborators.
   */
  public OwnedRecordService(
      RuntimeRequestContext context,
      RuntimeRecordModelResolver models,
      RuntimeRecordAccessPolicy policies,
      RecordSqlCompiler compiler,
      RuntimeCalciteRecordBackend backend,
      RecordRequestFingerprint fingerprints,
      RecordAdmissionController admission,
      RecordAuditSupport audit,
      RecordExecutionObserver observer,
      RecordCalculationWriteSupport calculations,
      RecordCalculationAccess calculationAccess) {
    this.context = context;
    this.models = models;
    this.policies = policies;
    this.compiler = compiler;
    this.backend = backend;
    this.fingerprints = fingerprints;
    this.admission = admission;
    this.audit = audit;
    this.observer = observer;
    this.calculations = calculations;
    this.calculationAccess = calculationAccess;
  }

  /** Reads an authorized parent and its owned child collections with consistent versions. */
  public OwnedRecordView get(OwnedRecordQuery query) {
    var actor = context.requireActor();
    var parent =
        models.resolve(actor, query.parent().physicalModelId(), query.parent().schemaReference());
    var access = policies.authorize(actor, parent);
    var children = children(parent, access, query.details());
    try (var ignored = admission.enter(actor.tenantId())) {
      return backend.read(parent, children.stream().map(Child::model).toList(), access, 30_000, session -> {
        var rules = calculationAccess.resolve(parent, access, session.calculation());
        var visible = rules.apply(parent, access);
        RecordView before = lookup(parent, visible, query.parent(), session.query());
        Map<String, List<RecordView>> values = new LinkedHashMap<>();
        for (var child : children) {
          var restricted = new Child(child.model(), rules.apply(child.model(), child.access()), child.reference(), child.parentCode());
          values.put(child.model().physicalModelId(), rows(restricted, before.id(), session.query()));
        }
        var after = lookup(parent, visible, new RecordLookupQuery(parent.physicalModelId(),
            query.parent().schemaReference(), before.id(), List.of("id")), session.query());
        if (before.version() != after.version()) {
          throw conflict();
        }
        return new OwnedRecordView(before, values);
      });
    }
  }

  /**
   * Previews authorized intent under the current policy and resource fences. The borrowed read
   * session exposes no write operation; validation and calculation share the commit preparation.
   */
  public OwnedRecordPreview preview(final OwnedRecordCommand command) {
    if (command.snapshot() != null || command.parent().mutations().getFirst() instanceof RecordDeleteMutation) {
      throw invalid("Preview accepts create or update intent without a snapshot");
    }
    var prepared = prepare(command);
    validateIntent(prepared, command);
    var scopes = calculationScopes(prepared);
    try (var ignored = admission.enter(prepared.access().actor().tenantId())) {
      return backend.read(prepared.model(), scopes.values().stream().map(RecordCalculationWriteSupport.Scope::model).toList(),
          prepared.access(), command.parent().timeoutMs(), session -> {
            if (session.revision() != command.parent().writePolicyVersion()) {
              throw new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_WRITE_POLICY_CONFLICT,
                  "Write policy changed before preview admission");
            }
            verifyCalculation(prepared, session.calculation());
            var rules = calculationAccess.resolve(prepared.model(), prepared.access(), session.calculation());
            return calculations.preview(prepared.calculation(),
                new RecordCalculationWriteSupport.Scope(prepared.model(), prepared.access()), scopes, command,
                parentId(prepared, command), prepared.operation(), session.query(),
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS), rules);
          });
    }
  }

  private RecordView lookup(final RuntimeRecordModel model, final RecordAccessContext access,
      final RecordLookupQuery query, final Function<RecordSelectPlan, CalciteQueryResult> reader) {
    var plan = compiler.lookup(model, access, query);
    var values = RecordQueryServiceImpl.views(reader.apply(plan), plan, model, access);
    if (values.isEmpty()) {
      throw new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_NOT_FOUND, "Record was not found in the active data scope");
    }
    return values.getFirst();
  }

  /** Returns the original confirmation for an exact tenant, actor and request identity. */
  public RecordCommandResult findResult(OwnedRecordCommand command) {
    var prepared = prepare(command);
    validateIntent(prepared, command);
    try (var ignored = admission.enter(prepared.access().actor().tenantId())) {
      return receipt(prepared, command);
    }
  }

  /** Snapshot reads verify the exact operation owner and reapply current field visibility. */
  public OwnedRecordView snapshot(OwnedRecordCommand command) {
    if (command.snapshot() == null) {
      throw invalid("This operation did not request a snapshot");
    }
    // The request identifies the business operation, never the formula used by its old snapshot.
    var prepared = prepare(command, false);
    try (var ignored = admission.enter(prepared.access().actor().tenantId())) {
      return backend.read(prepared.model(), List.of(), prepared.access(), command.parent().timeoutMs(), session -> {
        var receipt = session.receipt(prepared.operation());
        if (receipt == null) {
          return null;
        }
        var stored = session.snapshot(prepared.operation());
        if (stored == null || receipt.size() != 1
            || !stored.values().parent().id().equals(receipt.getFirst().recordId())
            || stored.values().parent().version() != receipt.getFirst().version()) {
          throw new DnaRuntimeException(DnaRuntimeErrorCode.PERSISTENCE_FAILED, "Committed snapshot is absent or inconsistent");
        }
        var historical = calculationAccess.resolve(prepared.model(), prepared.access(), session.historical(stored));
        var current = calculationAccess.resolve(prepared.model(), prepared.access(), session.calculation());
        var captured = stored.values();
        Map<String, List<RecordView>> details = new LinkedHashMap<>();
        for (var child : prepared.snapshotChildren()) {
          var rows = captured.details().get(child.model().physicalModelId());
          if (rows == null) {
            throw new DnaRuntimeException(DnaRuntimeErrorCode.PERSISTENCE_FAILED, "Snapshot collection is absent");
          }
          var access = historical.apply(child.model(), current.apply(child.model(), child.access()));
          details.put(child.model().physicalModelId(), rows.stream()
              .map(row -> snapshotProjection(row, access, child.model())).toList());
        }
        var parentAccess = historical.apply(prepared.model(), current.apply(prepared.model(), prepared.access()));
        return new OwnedRecordView(snapshotProjection(captured.parent(), parentAccess, prepared.model()), details);
      });
    }
  }

  private static RecordView snapshotProjection(
      RecordView row, RecordAccessContext access, RuntimeRecordModel model) {
    Map<String, Object> values = new LinkedHashMap<>();
    row.values()
        .forEach(
            (field, value) -> {
              if (access.readableFields().contains(field)) {
                values.put(
                    field,
                    access.maskedFields().contains(field) && value != null
                        ? "***"
                        : "***".equals(value)
                            ? value
                            : RecordWireValues.wireValue(model, field, value));
              }
            });
    return new RecordView(row.id(), row.version(), values);
  }

  /** Commits the parent and child mutations, receipt and optional snapshot atomically. */
  public RecordCommandResult execute(OwnedRecordCommand command) {
    var prepared = prepare(command);
    long started = System.nanoTime();
    try {
      var result = executeAuthorized(prepared, command);
      long duration = (System.nanoTime() - started) / 1_000_000;
      audit.success(
          org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditEventType
              .RECORD_TRANSACTION_SUCCEEDED,
          prepared.model(),
          prepared.access(),
          result.affectedRows(),
          duration);
      observer.record(
          "owned-transaction", "succeeded", prepared.model(), prepared.access(), duration);
      return result;
    } catch (RuntimeException failure) {
      long duration = (System.nanoTime() - started) / 1_000_000;
      audit.failure(
          org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditEventType
              .RECORD_TRANSACTION_FAILED,
          prepared.model(),
          prepared.access(),
          duration,
          failure instanceof DnaRuntimeException dnaFailure
              ? dnaFailure.errorCode()
              : DnaRuntimeErrorCode.RECORD_TRANSACTION_FAILED);
      observer.record("owned-transaction", "failed", prepared.model(), prepared.access(), duration);
      throw failure;
    }
  }

  private RecordCommandResult executeAuthorized(Prepared prepared, OwnedRecordCommand command) {
    validateIntent(prepared, command);
    try (var ignored = admission.enter(prepared.access().actor().tenantId())) {
      var committed = receipt(prepared, command);
      if (committed != null) {
        return committed;
      }
      try {
        var catalogChildren = new LinkedHashMap<String, RuntimeRecordModel>();
        prepared
            .children()
            .forEach(child -> catalogChildren.put(child.model().physicalModelId(), child.model()));
        prepared
            .snapshotChildren()
            .forEach(child -> catalogChildren.put(child.model().physicalModelId(), child.model()));
        prepared.calculationChildren().forEach(child -> catalogChildren.put(child.model().physicalModelId(), child.model()));
        List<RecordSqlStatement> preparedResults = new ArrayList<>(1);
        var result = backend.executePreparedOwned(prepared.model(), List.copyOf(catalogChildren.values()),
            new RecordWriteAdmission(prepared.access().actor().tenantId(), command.parent().writePolicyVersion(),
                prepared.access().resourceGeneration()), command.parent().timeoutMs(), prepared.operation(), (reader, at, installed) -> {
                  var binding = prepared.calculation();
                  verifyCalculation(prepared, installed);
                  var write = binding == null ? prepareWrite(prepared, command, reader, at)
                      : calculatedWrite(prepared, command, reader, at);
                  preparedResults.add(write.statements().getFirst());
                  return write;
                });
        if (result.replayedResults() != null) {
          return result.replayedResults().getFirst();
        }
        var first = preparedResults.getFirst();
        return new RecordCommandResult(
            first.mutationType(), first.recordId(), first.resultVersion(), 1, first.updatedAt());
      } catch (RuntimeException failure) {
        try {
          var recovered = receipt(prepared, command);
          if (recovered != null) {
            return recovered;
          }
        } catch (DnaRuntimeException recovery) {
          if (recovery.errorCode() == DnaRuntimeErrorCode.RECORD_IDEMPOTENCY_CONFLICT) {
            throw recovery;
          }
          failure.addSuppressed(recovery);
        }
        if (failure instanceof RecordMutationMissException) {
          throw conflict();
        }
        throw failure;
      }
    }
  }

  private RecordWritePreparation calculatedWrite(final Prepared prepared, final OwnedRecordCommand command,
      final Function<RecordSelectPlan, CalciteQueryResult> reader, final Instant at) {
    var statements = calculations.prepare(prepared.calculation(),
        new RecordCalculationWriteSupport.Scope(prepared.model(), prepared.access()), calculationScopes(prepared), command,
        parentId(prepared, command), prepared.operation(), reader, at);
    var snapshot = command.snapshot() == null ? null : snapshotPlan(prepared, command.parent().schemaReference(),
        parentId(prepared, command), statements.getFirst().resultVersion(), command.snapshot().requirements());
    return new RecordWritePreparation(statements, snapshot);
  }

  private static void verifyCalculation(final Prepared prepared, final RecordCalculationStore.Installed installed) {
    if (prepared.calculation() != null) {
      prepared.calculation().verify(installed);
    } else if (installed != null && !installed.policy().definition().isEmpty()) {
      throw invalid("Calculation metadata is absent from prepared intent");
    }
  }

  private static Map<String, RecordCalculationWriteSupport.Scope> calculationScopes(final Prepared prepared) {
    Map<String, RecordCalculationWriteSupport.Scope> scopes = new LinkedHashMap<>();
    prepared.calculationChildren().forEach(child -> scopes.put(child.model().physicalModelId(),
        new RecordCalculationWriteSupport.Scope(child.model(), child.access())));
    // Request-facing schemas take precedence so stable identities map historical payloads correctly.
    prepared.children().forEach(child -> scopes.put(child.model().physicalModelId(),
        new RecordCalculationWriteSupport.Scope(child.model(), child.access())));
    return scopes;
  }

  private RecordWritePreparation prepareWrite(Prepared prepared, OwnedRecordCommand command,
      Function<RecordSelectPlan, CalciteQueryResult> reader, Instant now) {
    var mutation = command.parent().mutations().getFirst();
    String parentId = parentId(prepared, command);
    boolean create = mutation instanceof RecordCreateMutation;
    boolean delete = mutation instanceof RecordDeleteMutation;
    if (!create) {
      var lookup = compiler.lookup(prepared.model(), prepared.access(),
          new RecordLookupQuery(prepared.model().physicalModelId(), command.parent().schemaReference(),
              parentId, List.of("id")));
      var currentRows = RecordQueryServiceImpl.views(reader.apply(lookup), lookup, prepared.model(), prepared.access());
      if (currentRows.isEmpty()) {
        throw new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_NOT_FOUND, "Record was not found in the authorized scope");
      }
      var current = currentRows.getFirst();
      long expected =
          mutation instanceof RecordUpdateMutation update
              ? update.expectedVersion()
              : ((RecordDeleteMutation) mutation).expectedVersion();
      if (current.version() != expected) {
        throw conflict();
      }
    }
    List<RecordSqlStatement> statements = new ArrayList<>();
    statements.add(
        compiler.mutation(
            prepared.model(), prepared.access(), mutation, now, create ? parentId : null));
    int total = 0;
    for (int index = 0; index < prepared.children().size(); index++) {
      Child child = prepared.children().get(index);
      var requested = command.details().get(index).rows();
      List<RecordView> existing = create ? List.of() : rows(child, parentId, reader);
      if (delete && !requested.isEmpty()) {
        throw invalid("Delete cannot introduce child rows");
      }
      total += delete ? existing.size() : requested.size();
      Map<String, RecordView> byId = new LinkedHashMap<>();
      existing.forEach(row -> byId.put(row.id(), row));
      for (int rowIndex = 0; rowIndex < requested.size(); rowIndex++) {
        var row = requested.get(rowIndex);
        var values = new LinkedHashMap<>(row.values());
        values.put(child.parentCode(), parentId);
        values.put("row_order", rowIndex);
        if (row.id() == null) {
          String id =
              RecordRequestFingerprint.deterministicRecordId(
                  prepared.access().actor().tenantId(),
                  child.model().physicalModelId(),
                  prepared.operation().id() + ":" + rowIndex);
          statements.add(
              compiler.mutation(
                  child.model(),
                  child.access(),
                  new RecordCreateMutation("child", values),
                  now,
                  id));
        } else {
          var old = byId.remove(row.id());
          if (old == null || old.version() != row.expectedVersion()) {
            throw conflict();
          }
          statements.add(
              compiler.mutation(
                  child.model(),
                  child.access(),
                  new RecordUpdateMutation("child", row.id(), row.expectedVersion(), values),
                  now));
        }
      }
      for (var removed : byId.values()) {
        statements.add(
            compiler.mutation(
                child.model(),
                child.access(),
                new RecordDeleteMutation("child", removed.id(), removed.version()),
                now));
      }
    }
    if (total > 200 || statements.size() > 401) {
      throw invalid("Aggregate row budget exceeded");
    }
    var snapshot =
        command.snapshot() == null
            ? null
            : snapshotPlan(
                prepared,
                command.parent().schemaReference(),
                parentId,
                statements.getFirst().resultVersion(), command.snapshot().requirements());
    return new RecordWritePreparation(statements, snapshot);
  }

  private Prepared prepare(OwnedRecordCommand command) {
    return prepare(command, true);
  }

  private Prepared prepare(OwnedRecordCommand command, boolean includeCalculation) {
    var actor = context.requireActor();
    var parent =
        models.resolve(
            actor, command.parent().physicalModelId(), command.parent().schemaReference());
    var access = policies.authorize(actor, parent);
    var children =
        children(parent, access, command.details().stream().map(OwnedRecordSet::model).toList());
    var operation = RecordOperationIdentity.of(actor, command.parent(), fingerprints.of(command));
    var snapshotChildren =
        command.snapshot() == null
            ? List.<Child>of()
            : children(parent, access, command.snapshot().details());
    var installed = includeCalculation
        ? backend.calculation(parent, actor.tenantId(), command.parent().writePolicyVersion(), command.parent().timeoutMs())
        : null;
    var calculation = calculations.bind(actor, parent, installed);
    var calculationChildren = calculation == null ? List.<Child>of() : children(parent, access,
        installed.policy().definition().details().values().stream()
            .map(scope -> new OwnedRecordModel(scope.physicalModelId(), scope.schemaReference())).toList());
    return new Prepared(parent, access, children, snapshotChildren, operation, calculation, calculationChildren);
  }

  private RecordSnapshotPlan snapshotPlan(
      Prepared prepared, RecordSchemaReference reference, String parentId, long version,
      List<org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotFieldRequirement> requirements) {
    List<RecordSnapshotPlan.Part> parts = new ArrayList<>();
    var model = prepared.model();
    var rules = calculationAccess.resolve(model, prepared.access(),
        prepared.calculation() == null ? null : prepared.calculation().installed());
    var parentAccess = rules.apply(model, prepared.access());
    var fields = projectionFields(model, parentAccess);
    if (fields.isEmpty()) {
      fields = List.of("id");
    }
    for (int offset = 0; offset < fields.size(); offset += 90) {
      var plan =
          compiler.lookup(
              model,
              parentAccess,
              new RecordLookupQuery(
                  model.physicalModelId(),
                  reference,
                  parentId,
                  fields.subList(offset, Math.min(fields.size(), offset + 90))));
      parts.add(new RecordSnapshotPlan.Part(model, parentAccess, plan, true));
    }
    for (var child : prepared.snapshotChildren()) {
      var childAccess = rules.apply(child.model(), child.access());
      var query =
          new RecordPageQuery(
              child.model().physicalModelId(),
              child.reference(),
              0,
              41,
              projectionFields(child.model(), childAccess),
              new RecordFilter(
                  RecordFilterOperator.EQUALS, child.parentCode(), List.of(parentId), List.of()),
              List.of(new RecordSort("row_order", RecordSortDirection.ASC)));
      parts.add(
          new RecordSnapshotPlan.Part(
              child.model(),
              childAccess,
              compiler.page(child.model(), childAccess, query),
              false));
    }
    return new RecordSnapshotPlan(parentId, version, List.copyOf(parts), new RecordSnapshotValidation(model, parentAccess, requirements));
  }

  private List<Child> children(
      RuntimeRecordModel parent, RecordAccessContext access, List<OwnedRecordModel> references) {
    List<Child> result = new ArrayList<>();
    for (var reference : references) {
      var model =
          models.resolve(access.actor(), reference.physicalModelId(), reference.schemaReference());
      var owner = model.activeSchema().owner();
      var rootTarget = parent.target().metadataTarget();
      var childTarget = model.target().metadataTarget();
      if (owner == null
          || !owner.parentModelId().equals(parent.physicalModelId())
          || !Objects.equals(owner, model.requestedSchema().owner())
          || !model.appId().equals(parent.appId())
          || !rootTarget.dataSourceId().equals(childTarget.dataSourceId())
          || !Objects.equals(rootTarget.catalogName(), childTarget.catalogName())
          || !Objects.equals(rootTarget.schemaName(), childTarget.schemaName())) {
        throw new DnaRuntimeException(
            DnaRuntimeErrorCode.RECORD_ACCESS_DENIED,
            "Child does not belong to this aggregate target");
      }
      String parentCode =
          model.requestedSchema().fields().stream()
              .filter(field -> field.fieldId().equals(owner.parentFieldId()))
              .findFirst()
              .orElseThrow(() -> invalid("Parent reference is absent"))
              .code();
      var permission = policies.owned(access.actor(), model, access);
      Set<String> readable = new LinkedHashSet<>(permission.readableFields());
      Set<String> writable = new LinkedHashSet<>(permission.writableFields());
      readable.addAll(Set.of(parentCode, "row_order"));
      writable.addAll(Set.of(parentCode, "row_order"));
      var childAccess =
          new RecordAccessContext(
              permission.actor(),
              permission.resourceCode(),
              readable,
              writable,
              permission.maskedFields(),
              permission.rowScope(),
              permission.organizationId(), permission.resourceGeneration());
      result.add(new Child(model, childAccess, reference.schemaReference(), parentCode));
    }
    return result;
  }

  private void validateIntent(Prepared prepared, OwnedRecordCommand command) {
    String parentId = parentId(prepared, command);
    var mutation = command.parent().mutations().getFirst();
    if (prepared.calculation() == null) {
      compiler.mutation(prepared.model(), prepared.access(), mutation, Instant.EPOCH,
          mutation instanceof RecordCreateMutation ? parentId : null);
    } else {
      compiler.validateIntent(prepared.model(), prepared.access(), mutation,
          prepared.calculation().outputs(prepared.model().physicalModelId()));
    }
    for (int index = 0; index < prepared.children().size(); index++) {
      var child = prepared.children().get(index);
      for (var row : command.details().get(index).rows()) {
        if (row.values().containsKey(child.parentCode()) || row.values().containsKey("row_order")) {
          throw invalid("Child ownership and order are server controlled");
        }
        var values = new LinkedHashMap<>(row.values());
        values.put(child.parentCode(), parentId);
        values.put("row_order", 0);
        var mutationRow = row.id() == null ? new RecordCreateMutation("child", values)
            : new RecordUpdateMutation("child", row.id(), row.expectedVersion(), values);
        if (prepared.calculation() == null) {
          compiler.mutation(child.model(), child.access(), mutationRow, Instant.EPOCH);
        } else {
          compiler.validateIntent(child.model(), child.access(), mutationRow,
              prepared.calculation().outputs(child.model().physicalModelId()));
        }
      }
    }
  }

  private static List<String> projectionFields(RuntimeRecordModel model, RecordAccessContext access) {
    return java.util.stream.Stream.concat(model.requestedFieldCodes().stream(),
        org.simplepoint.plugin.dna.runtime.api.record.RecordSystemField.storageCodes().stream())
        .filter(access.readableFields()::contains).distinct().toList();
  }

  private List<RecordView> rows(Child child, String parentId, Function<RecordSelectPlan, CalciteQueryResult> reader) {
    var query =
        new RecordPageQuery(
            child.model().physicalModelId(),
            child.reference(),
            0,
            41,
            projectionFields(child.model(), child.access()),
            new RecordFilter(
                RecordFilterOperator.EQUALS, child.parentCode(), List.of(parentId), List.of()),
            List.of(new RecordSort("row_order", RecordSortDirection.ASC)));
    var plan = compiler.page(child.model(), child.access(), query);
    var rows =
        RecordQueryServiceImpl.views(
            reader.apply(plan), plan, child.model(), child.access());
    if (rows.size() > 40) {
      throw invalid("Stored detail row budget exceeded");
    }
    return rows;
  }

  private RecordCommandResult receipt(Prepared prepared, OwnedRecordCommand command) {
    var results =
        backend.findResult(prepared.model(), prepared.operation(), command.parent().timeoutMs());
    return results == null ? null : results.getFirst();
  }

  private String parentId(Prepared prepared, OwnedRecordCommand command) {
    return switch (command.parent().mutations().getFirst()) {
      case RecordCreateMutation ignored ->
          RecordRequestFingerprint.deterministicRecordId(
              prepared.access().actor().tenantId(),
              prepared.model().physicalModelId(),
              "owned:" + command.parent().idempotencyKey());
      case RecordUpdateMutation update -> update.recordId();
      case RecordDeleteMutation delete -> delete.recordId();
    };
  }

  private static DnaRuntimeException invalid(String message) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_COMMAND_INVALID, message);
  }

  private static DnaRuntimeException conflict() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_VERSION_CONFLICT,
        "Aggregate record changed or a detail row is not owned by this record");
  }

  private record Child(
      RuntimeRecordModel model,
      RecordAccessContext access,
      RecordSchemaReference reference,
      String parentCode) {}

  private record Prepared(
      RuntimeRecordModel model,
      RecordAccessContext access,
      List<Child> children,
      List<Child> snapshotChildren,
      RecordOperationIdentity operation,
      RecordCalculationWriteSupport.Binding calculation,
      List<Child> calculationChildren) {}
}
