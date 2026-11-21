package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.app.form.service.FormDnaSchemaPublicationCoordinator.PreparedDnaSchema;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordModel;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordQuery;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordRow;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordSet;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordLookupQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope;
import org.simplepoint.plugin.dna.runtime.api.service.DnaOwnedRecordService;

/** Scoped writes reuse real APP publication, resource fences and native aggregate transactions. */
final class FormInverseWriteDatabaseAssertions {
  private FormInverseWriteDatabaseAssertions() {}

  static void verify(
      DnaOwnedRecordService service,
      PreparedDnaSchema model,
      RecordSchemaReference schema,
      OwnedRecordModel detail,
      String customer,
      String product,
      String recordId,
      String anotherRecord,
      String customerModel,
      String productModel) {
    var context = FormDnaReferenceRecordAssertions.user("trades", true);
    var scope =
        new RelatedRecordScope(
            "1", "product", productModel, product, RelatedRecordScope.Mode.MAINTAIN, null);
    var update =
        new RelatedRecordCommand(
            new OwnedRecordCommand(
                new RecordTransactionCommand(
                    model.physicalModelId(),
                    schema,
                    List.of(
                        new RecordUpdateMutation(
                            "update", recordId, 0, Map.of("record_state", "DRAFT"))),
                    30_000,
                    "inverse-update",
                    1),
                List.of()),
            scope);
    assertEquals(1, service.executeRelated(context, update).version());
    var aggregate = get(service, context, model, schema, detail, recordId);
    var row = aggregate.details().get(detail.physicalModelId()).getFirst();
    var foreignRow =
        get(service, context, model, schema, detail, anotherRecord)
            .details()
            .get(detail.physicalModelId())
            .getFirst();
    Map<String, Object> clear = new LinkedHashMap<>();
    clear.put("product_id", null);
    var payload =
        new OwnedRecordCommand(
            new RecordTransactionCommand(
                model.physicalModelId(),
                schema,
                List.of(
                    new RecordUpdateMutation(
                        "update",
                        recordId,
                        aggregate.parent().version(),
                        Map.of("record_state", "DRAFT"))),
                30_000,
                "inverse-unlink",
                1),
            List.of(
                new OwnedRecordSet(
                    detail, List.of(new OwnedRecordRow(row.id(), row.version(), clear)))));
    var wrong =
        new RelatedRecordCommand(
            payload,
            new RelatedRecordScope(
                "1",
                "product",
                productModel,
                product,
                RelatedRecordScope.Mode.UNLINK,
                foreignRow.id()));
    conflict(
        () -> service.executeRelated(context, wrong),
        DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT);
    assertEquals(
        product,
        get(service, context, model, schema, detail, recordId)
            .details()
            .get(detail.physicalModelId())
            .getFirst()
            .values()
            .get("product_id"));
    var parent = payload.parent();
    var fresh =
        new OwnedRecordCommand(
            new RecordTransactionCommand(
                parent.physicalModelId(),
                parent.schemaReference(),
                parent.mutations(),
                parent.timeoutMs(),
                "inverse-unlink-correct",
                parent.writePolicyVersion()),
            payload.details());
    var unlink =
        new RelatedRecordCommand(
            fresh,
            new RelatedRecordScope(
                "1", "product", productModel, product, RelatedRecordScope.Mode.UNLINK, row.id()));
    var unlinked = service.executeRelated(context, unlink);
    assertEquals(2, unlinked.version());
    assertEquals(unlinked, service.executeRelated(context, unlink));
    var current = get(service, context, model, schema, detail, recordId);
    assertEquals(1, current.details().get(detail.physicalModelId()).size());
    assertNull(
        current.details().get(detail.physicalModelId()).getFirst().values().get("product_id"));
    preservesOtherRowsOnUnlink(
        service, context, model, schema, detail, anotherRecord, productModel, product);
    var remove =
        new RelatedRecordCommand(
            new OwnedRecordCommand(
                new RecordTransactionCommand(
                    model.physicalModelId(),
                    schema,
                    List.of(
                        new RecordDeleteMutation("delete", recordId, current.parent().version())),
                    30_000,
                    "inverse-delete",
                    1),
                List.of()),
            new RelatedRecordScope(
                "1", "customer", customerModel, customer, RelatedRecordScope.Mode.MAINTAIN, null));
    var deleted = service.executeRelated(context, remove);
    assertEquals(deleted, service.executeRelated(context, remove));
    assertEquals(deleted, service.findRelatedResult(context, remove));
    conflict(
        () -> get(service, context, model, schema, detail, recordId),
        DnaRuntimeErrorCode.RECORD_NOT_FOUND);
  }

  private static void preservesOtherRowsOnUnlink(
      DnaOwnedRecordService service,
      DnaInvocationContext context,
      PreparedDnaSchema model,
      RecordSchemaReference schema,
      OwnedRecordModel detail,
      String recordId,
      String productModel,
      String product) {
    var before = get(service, context, model, schema, detail, recordId);
    var rows = before.details().get(detail.physicalModelId());
    org.junit.jupiter.api.Assertions.assertTrue(rows.size() >= 2);
    Map<String, Object> clear = new LinkedHashMap<>();
    clear.put("product_id", null);
    var requested =
        rows.stream()
            .map(
                row ->
                    new OwnedRecordRow(
                        row.id(),
                        row.version(),
                        row.id().equals(rows.getFirst().id()) ? clear : Map.<String, Object>of()))
            .toList();
    var scope =
        new RelatedRecordScope(
            "1",
            "product",
            productModel,
            product,
            RelatedRecordScope.Mode.UNLINK,
            rows.getFirst().id());
    java.util.function.BiFunction<String, List<OwnedRecordRow>, RelatedRecordCommand> command =
        (key, changes) ->
            new RelatedRecordCommand(
                new OwnedRecordCommand(
                    new RecordTransactionCommand(
                        model.physicalModelId(),
                        schema,
                        List.of(
                            new RecordUpdateMutation(
                                "update",
                                recordId,
                                before.parent().version(),
                                Map.of("record_state", "DRAFT"))),
                        30_000,
                        key,
                        1),
                    List.of(new OwnedRecordSet(detail, changes))),
                scope);
    conflict(
        () ->
            service.executeRelated(
                context,
                command.apply("unlink-cannot-remove-other-rows", List.of(requested.getFirst()))),
        DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT);
    conflict(
        () ->
            service.executeRelated(
                context, command.apply("unlink-cannot-reorder-other-rows", requested.reversed())),
        DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT);
    var proper = command.apply("unlink-preserves-other-rows", requested);
    var result = service.executeRelated(context, proper);
    assertEquals(result, service.executeRelated(context, proper));
    var after =
        get(service, context, model, schema, detail, recordId)
            .details()
            .get(detail.physicalModelId());
    assertEquals(
        rows.stream().map(row -> row.id()).toList(), after.stream().map(row -> row.id()).toList());
    assertNull(after.getFirst().values().get("product_id"));
    for (int index = 1; index < after.size(); index++) {
      assertEquals(product, after.get(index).values().get("product_id"));
    }
  }

  private static OwnedRecordView get(
      DnaOwnedRecordService service,
      DnaInvocationContext context,
      PreparedDnaSchema model,
      RecordSchemaReference schema,
      OwnedRecordModel detail,
      String id) {
    return service.get(
        context,
        new OwnedRecordQuery(
            new RecordLookupQuery(model.physicalModelId(), schema, id, List.of()),
            List.of(detail)));
  }

  private static void conflict(Runnable action, DnaRuntimeErrorCode expected) {
    assertEquals(expected, assertThrows(DnaRuntimeException.class, action::run).errorCode());
  }
}
