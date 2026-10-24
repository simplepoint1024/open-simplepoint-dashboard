package org.simplepoint.plugin.dna.runtime.service.record;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilter;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSort;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSortDirection;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter;

/** Complete tenant-owned aggregate input, stable keyset scans and exact immutable computed patches. */
final class RecordPublicationRows {
  private final RecordPublicationIntent intent;
  private final RuntimeRecordModel parent;
  private final Map<String, RuntimeRecordModel> details;
  private final RecordSqlCompiler sql;
  private final PhysicalSchemaCodec schemas;
  private final RecordCalculationEvaluator evaluator;
  private final StandardLogicalValueConverter values = new StandardLogicalValueConverter();

  RecordPublicationRows(final RecordPublicationIntent intent, final Map<String, RecordPublicationPatchCompiler.Resolved> models,
      final RecordSqlCompiler sql, final PhysicalSchemaCodec schemas) {
    this.intent = intent;
    this.parent = models.get(intent.key().modelId()).model();
    this.sql = sql;
    this.schemas = schemas;
    var scopes = new LinkedHashMap<String, RuntimeRecordModel>();
    intent.command().calculation().details().forEach((scope, definition) -> scopes.put(scope, models.get(definition.physicalModelId()).model()));
    this.details = Map.copyOf(scopes);
    this.evaluator = new RecordCalculationEvaluator(new RecordCalculationCompiler(schemas, Clock.fixed(intent.clock(), ZoneOffset.UTC)),
        parent, intent.command().calculation(), details);
  }

  RecordSelectPlan count() {
    var model = active(parent);
    return sql.count(model, access(model), null);
  }

  static long count(final CalciteQueryResult result) {
    try {
      if (result.truncated() || result.rows().size() != 1 || result.rows().getFirst().size() != 1) {
        throw invalid();
      }
      long count = new BigDecimal(result.rows().getFirst().getFirst().toString()).longValueExact();
      if (count < 0) {
        throw invalid();
      }
      return count;
    } catch (ArithmeticException | NumberFormatException | NullPointerException failure) {
      throw invalid();
    }
  }

  /** Read only identities for the batch; large aggregates are loaded and encoded one at a time. */
  List<String> identities(final String cursor, final int limit, final Function<RecordSelectPlan, CalciteQueryResult> reader) {
    if (cursor != null) {
      RecordPublicationAggregate.requireId(cursor);
    }
    var model = active(parent);
    var filter = cursor == null ? null : new RecordFilter(RecordFilterOperator.GREATER_THAN, "id", List.of(cursor), List.of());
    var plan = page(model, List.of("id"), filter, List.of(new RecordSort("id", RecordSortDirection.ASC)), limit);
    var result = reader.apply(plan);
    if (result.truncated() || result.rows().size() > limit) {
      throw invalid();
    }
    var ids = new LinkedHashSet<String>();
    for (var row : result.rows()) {
      if (row.size() != 2 || !(row.getFirst() instanceof String id) || id.equals(cursor) || !ids.add(id)) {
        throw invalid();
      }
      RecordPublicationAggregate.requireId(id);
    }
    // Preserve target collation order; Java ordering must not change the cursor sequence.
    return List.copyOf(ids);
  }

  RecordPublicationAggregate calculate(final String parentId, final Function<RecordSelectPlan, CalciteQueryResult> reader) {
    var root = read(parent, new RecordFilter(RecordFilterOperator.EQUALS, "id", List.of(parentId), List.of()), List.of(), 2, reader);
    if (root.size() != 1) {
      throw invalid();
    }
    var collections = new LinkedHashMap<String, List<RecordView>>();
    var inputs = new LinkedHashMap<String, List<Map<String, Object>>>();
    details.forEach((scope, model) -> {
      String parentColumn = model.activeSchema().fields().stream()
          .filter(field -> field.fieldId().equals(model.activeSchema().owner().parentFieldId())).findFirst().orElseThrow().code();
      var rows = read(model, new RecordFilter(RecordFilterOperator.EQUALS, parentColumn, List.of(parentId), List.of()),
          List.of(new RecordSort("row_order", RecordSortDirection.ASC), new RecordSort("id", RecordSortDirection.ASC)), 41, reader);
      if (rows.size() > 40) {
        throw invalid();
      }
      collections.put(scope, rows);
      inputs.put(scope, rows.stream().map(RecordView::values).toList());
    });
    var output = evaluator.evaluate(root.getFirst().values(), inputs);
    var patches = new ArrayList<RecordPublicationAggregate.Row>();
    patches.add(patch(parent, root.getFirst(), null, output.parent()));
    details.forEach((scope, model) -> {
      var rows = collections.get(scope);
      var computed = output.details().get(scope);
      if (computed == null || computed.size() != rows.size()) {
        throw invalid();
      }
      for (int index = 0; index < rows.size(); index++) {
        var patch = patch(model, rows.get(index), parentId, computed.get(index));
        if (!patch.changes().isEmpty()) {
          patches.add(patch);
        }
      }
    });
    return new RecordPublicationAggregate(1, parentId, patches);
  }

  private RecordPublicationAggregate.Row patch(final RuntimeRecordModel model, final RecordView row,
      final String parentId, final Map<String, Object> computed) {
    var changes = new LinkedHashMap<String, RecordPublicationAggregate.Change>();
    for (var entry : computed.entrySet()) {
      var field = model.activeSchema().fields().stream().filter(value -> value.code().equals(entry.getKey())).findFirst().orElseThrow();
      // Keep the public query wire shape; normalize exact values only at the durable staging boundary.
      var previous = values.normalize(field, row.values().get(field.code()));
      var candidate = values.normalize(field, entry.getValue());
      if (!previous.equals(candidate)) {
        changes.put(field.fieldId(), new RecordPublicationAggregate.Change(previous, candidate));
      }
    }
    return new RecordPublicationAggregate.Row(model.physicalModelId(), row.id(), parentId, row.version(),
        (String) row.values().get("updated_by"), (String) row.values().get("updated_at"), changes);
  }

  private List<RecordView> read(final RuntimeRecordModel historical, final RecordFilter filter, final List<RecordSort> sorts,
      final int limit, final Function<RecordSelectPlan, CalciteQueryResult> reader) {
    var model = active(historical);
    var fields = new ArrayList<>(model.requestedFieldCodes());
    fields.add("updated_by");
    fields.add("updated_at");
    var combined = new LinkedHashMap<String, RecordView>();
    // Only columns are chunked: each query reads the same bounded row set in the borrowed transaction.
    for (int offset = 0; offset < fields.size(); offset += 90) {
      var plan = page(model, fields.subList(offset, Math.min(fields.size(), offset + 90)), filter, sorts, limit);
      var result = reader.apply(plan);
      if (result.truncated() || result.rows().size() > limit) {
        throw invalid();
      }
      var rows = RecordQueryServiceImpl.views(result, plan, model, access(model));
      if (offset > 0 && !List.copyOf(combined.keySet()).equals(rows.stream().map(RecordView::id).toList())) {
        throw invalid();
      }
      var seen = new java.util.HashSet<String>();
      for (var row : rows) {
        if (!seen.add(row.id())) {
          throw invalid();
        }
        var previous = combined.get(row.id());
        var merged = new LinkedHashMap<>(row.values());
        if (previous != null) {
          if (previous.version() != row.version()) {
            throw invalid();
          }
          merged.putAll(previous.values());
        }
        combined.put(row.id(), new RecordView(row.id(), row.version(), java.util.Collections.unmodifiableMap(merged)));
      }
    }
    return List.copyOf(combined.values());
  }

  private RecordSelectPlan page(final RuntimeRecordModel model, final List<String> fields, final RecordFilter filter,
      final List<RecordSort> sorts, final int limit) {
    return sql.page(model, access(model), new RecordPageQuery(model.physicalModelId(),
        new RecordSchemaReference(model.activeVersion(), schemas.encode(model.activeSchema()).checksum()), 0, limit, fields, filter, sorts));
  }

  private RecordAccessContext access(final RuntimeRecordModel model) {
    var fields = new LinkedHashSet<>(model.requestedFieldCodes());
    fields.addAll(List.of("id", "record_version", "updated_by", "updated_at"));
    return new RecordAccessContext(new RuntimeActor(intent.key().tenantId(), intent.actorId()), null, fields, Set.of(), Set.of(), null, null);
  }

  private static RuntimeRecordModel active(final RuntimeRecordModel model) {
    return new RuntimeRecordModel(model.physicalModelId(), model.appId(), model.formId(), model.activeVersion(), 
        model.activeSchema(), model.target());
  }

  private static DnaRuntimeException invalid() {
    return new DnaRuntimeException(DnaRuntimeErrorCode.PERSISTENCE_FAILED, "Publication aggregate input is incomplete or inconsistent");
  }
}
