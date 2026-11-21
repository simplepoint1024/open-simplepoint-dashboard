package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.simplepoint.plugin.app.form.service.FormDnaSchemaPublicationCoordinator.PreparedDnaSchema;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordModel;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordRow;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordSet;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilter;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.service.DnaOwnedRecordService;

/** Real installed APP relationships exercise both owning scopes over each native database. */
final class FormInverseRecordDatabaseAssertions {
  private FormInverseRecordDatabaseAssertions() {}

  static void verify(
      DnaOwnedRecordService records,
      PreparedDnaSchema model,
      RecordSchemaReference schema,
      OwnedRecordModel detail,
      String customer,
      String product,
      String first,
      String customerModel,
      String productModel) {
    var targets = Map.of("customer", customerModel, "product", productModel);
    var context = FormDnaReferenceRecordAssertions.user("trades", true);
    Set<String> expected = new HashSet<>(Set.of(first));
    for (int index = 1; index <= 60; index++) {
      var rows =
          IntStream.range(0, index == 1 ? 40 : 2)
              .mapToObj(
                  row ->
                      new OwnedRecordRow(null, null, Map.<String, Object>of("product_id", product)))
              .toList();
      var command =
          new OwnedRecordCommand(
              new RecordTransactionCommand(
                  model.physicalModelId(),
                  schema,
                  List.of(
                      new RecordCreateMutation(
                          "create", Map.of("customer_id", customer, "record_state", "DRAFT"))),
                  30_000,
                  "inverse-" + index,
                  1),
              List.of(new OwnedRecordSet(detail, rows)));
      expected.add(records.execute(context, command).recordId());
    }
    for (var entry : Map.of("customer", customer, "product", product).entrySet()) {
      var firstPage =
          records.pageRelated(
              context,
              query(
                  model,
                  schema,
                  entry.getKey(),
                  targets.get(entry.getKey()),
                  entry.getValue(),
                  0,
                  null));
      var secondPage =
          records.pageRelated(
              context,
              query(
                  model,
                  schema,
                  entry.getKey(),
                  targets.get(entry.getKey()),
                  entry.getValue(),
                  1,
                  null));
      assertEquals(61, firstPage.total());
      assertEquals(61, secondPage.total());
      assertEquals(50, firstPage.records().size());
      assertEquals(11, secondPage.records().size());
      Set<String> actual = new HashSet<>();
      firstPage.records().forEach(record -> assertTrue(actual.add(record.id())));
      secondPage.records().forEach(record -> assertTrue(actual.add(record.id())));
      assertEquals(expected, actual);
      var filtered =
          records.pageRelated(
              context,
              query(
                  model,
                  schema,
                  entry.getKey(),
                  targets.get(entry.getKey()),
                  entry.getValue(),
                  0,
                  new RecordFilter(RecordFilterOperator.EQUALS, "id", List.of(first), List.of())));
      assertEquals(1, filtered.total());
      assertEquals(first, filtered.records().getFirst().id());
      assertEquals(
          0,
          records
              .pageRelated(
                  context,
                  query(
                      model,
                      schema,
                      entry.getKey(),
                      targets.get(entry.getKey()),
                      entry.getValue(),
                      0,
                      new RecordFilter(
                          RecordFilterOperator.EQUALS,
                          "record_state",
                          List.of("SUBMITTED"),
                          List.of())))
              .total());
    }
    var selection = query(model, schema, "product", productModel, product, 0, null);
    assertThrows(
        DnaRuntimeException.class,
        () ->
            records.pageRelated(FormDnaReferenceRecordAssertions.user("trades", false), selection));
    assertThrows(
        DnaRuntimeException.class,
        () ->
            records.pageRelated(
                context,
                query(
                    model,
                    schema,
                    "product",
                    productModel,
                    UUID.randomUUID().toString(),
                    0,
                    null)));
    assertThrows(
        DnaRuntimeException.class,
        () ->
            records.pageRelated(
                context, query(model, schema, "unknown", productModel, product, 0, null)));
    assertThrows(
        DnaRuntimeException.class,
        () ->
            records.pageRelated(
                context, query(model, schema, "product", customerModel, product, 0, null)));
    var self =
        new DnaInvocationContext(
            context.tenantId(),
            "another-reader",
            null,
            context.resourceCode(),
            false,
            context.resources(),
            "SELF",
            Set.of(),
            false,
            List.of(),
            Map.of(),
            context.additionalResourcePolicies());
    assertEquals(0, records.pageRelated(self, selection).total());
    var tenant =
        new DnaInvocationContext(
            "tenant-b",
            context.actorId(),
            null,
            context.resourceCode(),
            false,
            context.resources(),
            "ALL",
            Set.of(),
            false,
            List.of(),
            Map.of(),
            context.additionalResourcePolicies());
    assertThrows(DnaRuntimeException.class, () -> records.pageRelated(tenant, selection));
    var targetSelf = new java.util.LinkedHashMap<>(context.additionalResourcePolicies());
    targetSelf.put(
        "products",
        new org.simplepoint.plugin.dna.runtime.api.context.DnaResourcePolicy(
            "1", "SELF", Set.of(), false, List.of(), Map.of()));
    var narrowTarget =
        new DnaInvocationContext(
            context.tenantId(),
            "another-reader",
            null,
            context.resourceCode(),
            false,
            context.resources(),
            "ALL",
            Set.of(),
            false,
            List.of(),
            Map.of(),
            targetSelf);
    assertThrows(DnaRuntimeException.class, () -> records.pageRelated(narrowTarget, selection));
    for (String permission : List.of("HIDDEN", "MASKED")) {
      var restricted =
          fields(
              context,
              Map.of(
                  RecordPermissionModel.key(detail.physicalModelId())
                      + "#"
                      + RecordPermissionModel.field("product"),
                  permission));
      assertThrows(DnaRuntimeException.class, () -> records.pageRelated(restricted, selection));
      var rootRestricted =
          fields(
              context,
              Map.of(
                  RecordPermissionModel.key(model.physicalModelId())
                      + "#"
                      + RecordPermissionModel.field("customer"),
                  permission));
      assertThrows(
          DnaRuntimeException.class,
          () ->
              records.pageRelated(
                  rootRestricted,
                  query(model, schema, "customer", customerModel, customer, 0, null)));
    }
    FormInverseWriteDatabaseAssertions.verify(
        records,
        model,
        schema,
        detail,
        customer,
        product,
        first,
        expected.stream().filter(id -> !id.equals(first)).findFirst().orElseThrow(),
        customerModel,
        productModel);
  }

  private static RelatedRecordPageQuery query(
      PreparedDnaSchema model,
      RecordSchemaReference schema,
      String relation,
      String targetModel,
      String target,
      int page,
      RecordFilter filter) {
    return new RelatedRecordPageQuery(
        new RecordPageQuery(
            model.physicalModelId(), schema, page, 50, List.of("customer_id"), filter, List.of()),
        relation,
        targetModel,
        target);
  }

  private static DnaInvocationContext fields(
      DnaInvocationContext source, Map<String, String> fields) {
    return new DnaInvocationContext(
        source.tenantId(),
        source.actorId(),
        source.organizationId(),
        source.resourceCode(),
        source.administrator(),
        source.resources(),
        source.dataScopeType(),
        source.departmentIds(),
        source.dataScopeIncludeSelf(),
        source.dataScopeExpressions(),
        fields,
        source.additionalResourcePolicies());
  }
}
