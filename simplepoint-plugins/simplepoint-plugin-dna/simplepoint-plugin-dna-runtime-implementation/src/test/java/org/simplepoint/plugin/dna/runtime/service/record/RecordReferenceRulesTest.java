package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.*;
import org.simplepoint.plugin.dna.runtime.api.schema.*;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;

class RecordReferenceRulesTest {
  private static final String RECORD = UUID.randomUUID().toString();
  private static final String CUSTOMER = UUID.randomUUID().toString();
  private static final LogicalFieldDefinition REFERENCE =
      new LogicalFieldDefinition(
          "customer",
          "customer_id",
          "Customer",
          LogicalFieldType.UUID,
          true,
          null,
          null,
          null,
          null);
  private static final LogicalFieldDefinition STATUS =
      new LogicalFieldDefinition(
          "status", "status", "Status", LogicalFieldType.STRING, true, 32, null, null, null);

  @Test
  void comparisonUsesStoredValuesAndChecksExplicitSubmissionConditionAfterMerge() {
    var rules =
        rules(definition(Map.of(), List.of(rule("customer", "customer", "target"))), root());
    var saved = Map.<String, Object>of("customer_id", CUSTOMER, "status", "DRAFT");
    assertThat(rules.checks("parent", RECORD, null, saved, saved).getFirst().requireAuthorization())
        .isTrue();
    assertThat(
            rules.checks("parent", RECORD, saved, saved, saved).getFirst().requireAuthorization())
        .isFalse();
    var submitted = Map.<String, Object>of("customer_id", CUSTOMER, "status", "SUBMITTED");
    assertThat(
            rules
                .checks("parent", RECORD, saved, submitted, submitted)
                .getFirst()
                .requireAuthorization())
        .isTrue();
    var changed =
        Map.<String, Object>of("customer_id", UUID.randomUUID().toString(), "status", "DRAFT");
    assertThat(
            rules
                .checks("parent", RECORD, saved, changed, changed)
                .getFirst()
                .requireAuthorization())
        .isTrue();
    var cleared = new HashMap<>(saved);
    cleared.put("customer_id", null);
    var clear = rules.checks("parent", RECORD, saved, cleared, cleared).getFirst();
    assertThat(clear.targetRecordId()).isNull();
    assertThat(clear.requireAuthorization()).isFalse();
    var upperCase =
        Map.<String, Object>of("customer_id", CUSTOMER.toUpperCase(), "status", "DRAFT");
    assertThat(
            rules
                .checks("parent", RECORD, saved, upperCase, upperCase)
                .getFirst()
                .requireAuthorization())
        .isFalse();
  }

  @Test
  void missingRetargetedAndCalculatedReferencePoliciesCannotBypassThePhysicalDefinition() {
    for (var policy :
        List.of(
            definition(Map.of(), List.of()),
            definition(Map.of(), List.of(rule("other", "customer", "target"))),
            definition(Map.of(), List.of(rule("customer", "customer", "different"))),
            definition(Map.of("customer", "1"), List.of(rule("customer", "customer", "target"))))) {
      assertThatThrownBy(() -> rules(policy, root()))
          .isInstanceOfSatisfying(
              DnaRuntimeException.class,
              failure ->
                  assertThat(failure.errorCode())
                      .isEqualTo(DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE));
    }
  }

  @Test
  void eligibilityUsesStableTargetFieldIdentitiesAcrossColumnRename() {
    var renamed =
        new LogicalFieldDefinition(
            "status",
            "current_status",
            "Status",
            LogicalFieldType.STRING,
            true,
            32,
            null,
            null,
            null);
    var target =
        new RuntimeRecordModel(
            "target",
            "app",
            "target-form",
            2,
            new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(renamed), List.of()),
            new PhysicalSchemaTarget(
                "target", new RuntimeMetadataTarget("test", "catalog", "schema", "target")));
    assertThat(RecordReferenceRules.targetCondition(target, condition("parent", "status")))
        .extracting(RecordValueCondition::field)
        .isEqualTo("current_status");
    assertThatThrownBy(
            () ->
                RecordReferenceRules.targetCondition(target, condition("parent", "current_status")))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(
            () -> RecordReferenceRules.targetCondition(target, condition("other-detail", "status")))
        .isInstanceOf(DnaRuntimeException.class);
  }

  private static RecordReferenceRules rules(
      RecordCalculationDefinition definition, RuntimeRecordModel model) {
    return new RecordReferenceRules(
        model,
        Map.of(),
        definition,
        Map.of("parent", Map.of("customer", REFERENCE, "status", STATUS)));
  }

  private static RuntimeRecordModel root() {
    return new RuntimeRecordModel(
        "source",
        "app",
        "source-form",
        1,
        new PhysicalSchemaDefinition(
            DnaRuntimeApiVersion.CURRENT,
            List.of(REFERENCE, STATUS),
            List.of(),
            null,
            null,
            List.of(new LogicalReferenceDefinition("customer", "customer", "target", false))),
        new PhysicalSchemaTarget(
            "source", new RuntimeMetadataTarget("test", "catalog", "schema", "source")));
  }

  private static RecordCalculationDefinition definition(
      Map<String, String> formulas, List<RecordReferenceRule> rules) {
    return new RecordCalculationDefinition("1", formulas, Map.of(), null, null, null, null, rules);
  }

  private static RecordReferenceRule rule(String relation, String field, String model) {
    return new RecordReferenceRule(
        "1",
        relation,
        new RecordValidationField("parent", field),
        model,
        "app",
        "target-form",
        "target-resource",
        null,
        condition("parent", "status"));
  }

  private static RecordValidationCondition condition(String scope, String field) {
    return new RecordValidationCondition(
        RecordValueConditionOperator.EQUALS,
        new RecordValidationField(scope, field),
        List.of(new LogicalValue(LogicalValueKind.STRING, "SUBMITTED")),
        List.of());
  }
}
