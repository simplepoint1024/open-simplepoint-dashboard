package org.simplepoint.plugin.app.form.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormCondition;
import org.simplepoint.plugin.app.form.api.model.FormConditionOperator;
import org.simplepoint.plugin.app.form.api.model.FormDetailDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceContextFilter;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceSearchContext;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceView;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationCardinality;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationField;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTarget;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSort;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSortDirection;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;
import org.simplepoint.plugin.dna.runtime.api.service.DnaFormRuntimeService;

/** Exercises APP's typed context compiler against the actual DNA query and permission boundary. */
public final class FormReferenceContextDatabaseAssertions {
  private static final String ORGANIZATION = "00000000-0000-0000-0000-000000000001";

  private FormReferenceContextDatabaseAssertions() {}

  /** Defines real target columns independently of the source context field identities. */
  public static ApplicationFormDocument targetDocument() {
    return new ApplicationFormDocument(
        "1",
        List.of(
            field("name", FormFieldType.STRING),
            field("region", FormFieldType.STRING),
            field("branch", FormFieldType.STRING),
            field("organization", FormFieldType.UUID),
            field("active", FormFieldType.BOOLEAN)),
        List.of(),
        List.of(),
        List.of());
  }

  /** Verifies filtering, counts, paging and data/field authorization on the native database. */
  public static void verify(DnaFormRuntimeService dna, String model, RecordSchemaReference schema) {
    var writer = user("tenant-a", "writer", "ALL", Map.of());
    var mutations = new ArrayList<RecordMutation>();
    for (int index = 0; index < 65; index++) {
      mutations.add(
          new RecordCreateMutation(
              "customer-" + index,
              Map.of(
                  "stored_name",
                  "客户・日本語😀" + String.format("%03d", index),
                  "stored_region",
                  index == 61 ? "华南" : "华北",
                  "stored_branch",
                  index == 62 ? "大阪" : "東京",
                  "stored_organization",
                  index == 63 ? "00000000-0000-0000-0000-000000000002" : ORGANIZATION,
                  "stored_active",
                  index != 64,
                  "record_state",
                  "DRAFT")));
    }
    dna.executeRecordTransaction(
        writer, new RecordTransactionCommand(model, schema, mutations, 30_000));
    var relation =
        new FormRelationDefinition(
            "1",
            "customer",
            FormRelationCardinality.MANY_TO_ONE,
            new FormRelationField("lines", "customer"),
            new FormRelationTarget("app", "form", "resource"),
            null,
            new FormReferenceView(
                "name",
                List.of("name"),
                List.of(),
                new FormCondition(
                    FormConditionOperator.EQUALS,
                    "active",
                    List.of(new FormValue(FormValueKind.BOOLEAN, "true")),
                    List.of()),
                false,
                List.of(
                    new FormReferenceContextFilter(
                        "region",
                        FormConditionOperator.EQUALS,
                        FormReferenceContextFilter.Source.FORM,
                        "region"),
                    new FormReferenceContextFilter(
                        "branch",
                        FormConditionOperator.EQUALS,
                        FormReferenceContextFilter.Source.ROW,
                        "region"),
                    new FormReferenceContextFilter(
                        "organization",
                        FormConditionOperator.EQUALS,
                        FormReferenceContextFilter.Source.ORGANIZATION,
                        null))));
    var source =
        new ApplicationFormDocument(
            "1",
            List.of(field("region", FormFieldType.STRING)),
            List.of(),
            List.of(),
            List.of(),
            List.of(
                new FormDetailDefinition(
                    "lines",
                    "lines",
                    "明细",
                    List.of(
                        field("region", FormFieldType.STRING),
                        new FormFieldDefinition(
                            "customer",
                            "customer_id",
                            "客户",
                            "客户",
                            FormFieldType.UUID,
                            FormStorageMode.REFERENCE,
                            false,
                            false,
                            null,
                            null,
                            null,
                            null,
                            List.of())),
                    List.of())),
            List.of(),
            null,
            List.of(relation));
    var targetFields =
        targetDocument().fields().stream()
            .collect(Collectors.toMap(FormFieldDefinition::fieldId, field -> field));
    var condition =
        FormReferenceContextPolicy.resolve(
            source,
            relation,
            targetFields,
            new FormReferenceSearchContext(
                Map.of("region", new FormValue(FormValueKind.STRING, "华北")),
                Map.of("region", new FormValue(FormValueKind.STRING, "東京"))),
            ORGANIZATION,
            (scope, field) -> {});
    assertTrue(condition.ready());
    var order = List.of(new RecordSort("stored_name", RecordSortDirection.ASC));
    var first =
        new RecordPageQuery(
            model, schema, 0, 50, List.of("stored_name"), null, order, condition.condition());
    var second =
        new RecordPageQuery(
            model, schema, 1, 50, List.of("stored_name"), null, order, condition.condition());
    var pageOne = dna.pageConditionedRecords(writer, first);
    var pageTwo = dna.pageConditionedRecords(writer, second);
    assertEquals(61, pageOne.total());
    assertEquals(61, pageTwo.total());
    assertEquals(50, pageOne.records().size());
    assertEquals(11, pageTwo.records().size());
    assertEquals("客户・日本語😀050", pageTwo.records().getFirst().values().get("stored_name"));
    assertEquals(
        0,
        dna.pageConditionedRecords(user("tenant-a", "another-user", "SELF", Map.of()), first)
            .total());
    assertThrows(
        DnaRuntimeException.class,
        () -> dna.pageConditionedRecords(user("tenant-b", "writer", "ALL", Map.of()), first));
    for (String access : List.of("HIDDEN", "MASKED")) {
      var permissions =
          Map.of(
              RecordPermissionModel.key(model) + "#" + RecordPermissionModel.field("region"),
              access);
      assertThrows(
          DnaRuntimeException.class,
          () -> dna.pageConditionedRecords(user("tenant-a", "writer", "ALL", permissions), first));
    }
  }

  private static DnaInvocationContext user(
      String tenant, String actor, String scope, Map<String, String> permissions) {
    return new DnaInvocationContext(
        tenant,
        actor,
        ORGANIZATION,
        "context-customers",
        false,
        Set.of("context-customers"),
        scope,
        Set.of(),
        false,
        List.of(),
        permissions);
  }

  private static FormFieldDefinition field(String id, FormFieldType type) {
    return new FormFieldDefinition(
        id,
        "stored_" + id,
        id,
        id,
        type,
        FormStorageMode.STORED,
        false,
        false,
        type == FormFieldType.STRING ? 120 : null,
        null,
        null,
        null,
        List.of());
  }
}
