package org.simplepoint.plugin.dna.runtime.service.record;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilter;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordLookupQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSort;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSortDirection;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope;
import org.simplepoint.plugin.dna.runtime.service.record.RecordCalculationWriteSupport.Scope;

/**
 * Unlink changes one nullable reference; normal aggregate replacement must not remove other rows.
 */
final class RecordRelatedUnlink {
  private RecordRelatedUnlink() {}

  static void verify(
      Scope parent,
      Map<String, Scope> models,
      RecordRelatedSelection selection,
      RelatedRecordScope scope,
      OwnedRecordCommand command,
      String parentId,
      RecordWritePreparation write,
      RecordSqlCompiler compiler,
      Function<RecordSelectPlan, CalciteQueryResult> reader) {
    if (write.statements().stream()
        .anyMatch(
            statement ->
                statement.subject() != null
                    && statement.mutationType() != RecordMutationType.UPDATE)) {
      throw conflict();
    }
    boolean owned = scope.sourceRowId() != null;
    if (!owned && !command.details().isEmpty()
        || owned
            && (command.details().size() != 1
                || !command
                    .details()
                    .getFirst()
                    .model()
                    .physicalModelId()
                    .equals(selection.source().physicalModelId()))) {
      throw conflict();
    }
    var update = (RecordUpdateMutation) command.parent().mutations().getFirst();
    var parentFields = new ArrayList<>(update.values().keySet());
    if (!owned) {
      parentFields.remove(selection.sourceCode());
    }
    for (var fields : batches(parentFields)) {
      var plan =
          compiler.lookup(
              parent.model(),
              parent.access(),
              new RecordLookupQuery(
                  parent.model().physicalModelId(),
                  command.parent().schemaReference(),
                  parentId,
                  fields));
      var rows = records(parent, plan, reader);
      if (rows.size() != 1) {
        throw conflict();
      }
      for (String field : fields) {
        unchanged(
            parent.model(), field, update.values().get(field), rows.getFirst().values().get(field));
      }
    }
    if (!owned) {
      return;
    }
    var requested = command.details().getFirst();
    var source = models.get(requested.model().physicalModelId());
    if (source == null) {
      throw conflict();
    }
    String ownerField = OwnedRecordBinding.parentCode(parent.model(), source.model());
    String referenceField =
        source.model().requestedSchema().fields().stream()
            .filter(field -> field.fieldId().equals(selection.rule().source().fieldId()))
            .findFirst()
            .orElseThrow(RecordRelatedUnlink::conflict)
            .code();
    var fields = new LinkedHashSet<String>();
    fields.add("row_order");
    requested.rows().forEach(row -> fields.addAll(row.values().keySet()));
    for (var projection : batches(new ArrayList<>(fields))) {
      var query =
          new RecordPageQuery(
              source.model().physicalModelId(),
              requested.model().schemaReference(),
              0,
              41,
              projection,
              new RecordFilter(
                  RecordFilterOperator.EQUALS, ownerField, List.of(parentId), List.of()),
              List.of(new RecordSort("row_order", RecordSortDirection.ASC)));
      var plan = compiler.page(source.model(), source.access(), query);
      var existing = records(source, plan, reader);
      if (!existing.stream()
          .map(RecordView::id)
          .toList()
          .equals(requested.rows().stream().map(row -> row.id()).toList())) {
        throw conflict();
      }
      for (int index = 0; index < existing.size(); index++) {
        var old = existing.get(index);
        var row = requested.rows().get(index);
        if (row.expectedVersion() == null || row.expectedVersion() != old.version()) {
          throw conflict();
        }
        for (String field : projection) {
          if (!row.values().containsKey(field)
              || row.id().equals(scope.sourceRowId()) && field.equals(referenceField)) {
            continue;
          }
          unchanged(source.model(), field, row.values().get(field), old.values().get(field));
        }
      }
    }
  }

  private static List<RecordView> records(
      Scope scope, RecordSelectPlan plan, Function<RecordSelectPlan, CalciteQueryResult> reader) {
    var result = reader.apply(plan);
    if (result.truncated()) {
      throw conflict();
    }
    return RecordQueryServiceImpl.views(result, plan, scope.model(), scope.access());
  }

  private static List<List<String>> batches(List<String> fields) {
    var result = new ArrayList<List<String>>();
    for (int offset = 0; offset < fields.size(); offset += 88) {
      result.add(fields.subList(offset, Math.min(fields.size(), offset + 88)));
    }
    return result;
  }

  private static void unchanged(
      RuntimeRecordModel model, String field, Object requested, Object stored) {
    var definition = model.requestedField(field);
    if (definition == null) {
      throw conflict();
    }
    Object left = RecordCalculationValues.typed(definition, requested);
    Object right = RecordCalculationValues.typed(definition, stored);
    boolean same =
        left instanceof BigDecimal a && right instanceof BigDecimal b
            ? a.compareTo(b) == 0
            : Objects.equals(left, right);
    if (!same) {
      throw conflict();
    }
  }

  private static DnaRuntimeException conflict() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT,
        "Unlink may only clear the selected reference without changing other inputs or row"
            + " membership");
  }
}
