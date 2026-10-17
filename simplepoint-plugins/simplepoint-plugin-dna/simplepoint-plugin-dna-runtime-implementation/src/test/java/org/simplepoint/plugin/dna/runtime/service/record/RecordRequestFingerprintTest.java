package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCompletenessQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordJsonSemantics;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueCondition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueConditionOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordViolationQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;

class RecordRequestFingerprintTest {

  private static final RecordSchemaReference SCHEMA = new RecordSchemaReference(
      1,
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  );

  private final RecordRequestFingerprint fingerprints = new RecordRequestFingerprint(
      new ObjectMapper().findAndRegisterModules()
  );

  @Test
  void canonicalizesMapOrderAndEquivalentNumbers() {
    Map<String, Object> first = new LinkedHashMap<>();
    first.put("title", "Order");
    first.put("amount", 10);
    Map<String, Object> second = new LinkedHashMap<>();
    second.put("amount", new BigDecimal("10.00"));
    second.put("title", "Order");

    assertThat(fingerprints.of(command(first)))
        .isEqualTo(fingerprints.of(command(second)));
  }

  @Test
  void changesWhenThePayloadChanges() {
    assertThat(fingerprints.of(command(Map.of("title", "First"))))
        .isNotEqualTo(fingerprints.of(command(Map.of("title", "Second"))));
  }

  @Test
  void transactionIdentityIncludesOrderedMutationsExpectedVersionsAndSchemaButNotTimeout() {
    var update = new RecordUpdateMutation("update", "record-a", 4, Map.of("amount", 10));
    var equivalent = new RecordUpdateMutation("update", "record-a", 4, Map.of("amount", new BigDecimal("10.00")));
    var delete = new RecordDeleteMutation("delete", "record-b", 2);
    String first = fingerprints.of(new RecordTransactionCommand("model-a", SCHEMA, List.of(update, delete), 1000, "key"));
    assertThat(fingerprints.of(new RecordTransactionCommand("model-a", SCHEMA, List.of(equivalent, delete), 2000, "key")))
        .isEqualTo(first);
    assertThat(fingerprints.of(new RecordTransactionCommand("model-a", SCHEMA, List.of(delete, update), 1000, "key")))
        .isNotEqualTo(first);
    assertThat(fingerprints.of(new RecordTransactionCommand("model-a", SCHEMA,
        List.of(new RecordUpdateMutation("update", "record-a", 5, Map.of("amount", 10)), delete), 1000, "key")))
        .isNotEqualTo(first);
    assertThat(fingerprints.of(new RecordTransactionCommand("model-a", new RecordSchemaReference(2, "b".repeat(64)),
        List.of(update, delete), 1000, "key"))).isNotEqualTo(first);
    assertThat(RecordRequestFingerprint.deterministicOperationId("tenant-a", "model-a", "key"))
        .isNotEqualTo(RecordRequestFingerprint.deterministicOperationId("tenant-b", "model-a", "key"))
        .isNotEqualTo(RecordRequestFingerprint.deterministicRecordId("tenant-a", "model-a", "key"));
  }

  @Test
  void derivesStableIdsInsideTheTenantAndModelScope() {
    String first = RecordRequestFingerprint.deterministicRecordId(
        "tenant-a",
        "model-a",
        "request-1"
    );

    assertThat(RecordRequestFingerprint.deterministicRecordId(
        "tenant-a",
        "model-a",
        "request-1"
    )).isEqualTo(first);
    assertThat(RecordRequestFingerprint.deterministicRecordId(
        "tenant-b",
        "model-a",
        "request-1"
    )).isNotEqualTo(first);
  }

  @Test
  void admissionVersionDoesNotChangeTheCommittedBusinessOperationIdentity() {
    var oldCreate = command(Map.of("title", "First"));
    assertThat(fingerprints.of(new RecordCreateCommand(oldCreate.physicalModelId(), SCHEMA,
        oldCreate.idempotencyKey(), oldCreate.values(), 8))).isEqualTo(fingerprints.of(oldCreate));
    var update = new RecordUpdateMutation("update", "record-a", 4, Map.of("amount", 10));
    assertThat(fingerprints.of(new RecordTransactionCommand("model-a", SCHEMA, List.of(update), 1000, "key", 8)))
        .isEqualTo(fingerprints.of(new RecordTransactionCommand("model-a", SCHEMA, List.of(update), 1000, "key")));
  }

  @Test
  void cancellationUsesDistinctIdentityForTheExactIntentAndIgnoresOnlyItsRetryBudget() {
    var original = new RecordWritePolicyPublicationCommand("model-a", "app", "form", SCHEMA, 0, 1,
        "publish", "b".repeat(64), Map.of(), 1000);
    var retry = new RecordWritePolicyPublicationCommand("model-a", "app", "form", SCHEMA, 0, 1,
        "publish", "b".repeat(64), Map.of(), 2000);
    var changed = new RecordWritePolicyPublicationCommand("model-a", "app", "form", SCHEMA, 0, 1,
        "publish", "c".repeat(64), Map.of(), 1000);
    assertThat(fingerprints.cancellationOf(original)).isNotEqualTo(fingerprints.of(original))
        .isEqualTo(fingerprints.cancellationOf(retry)).isNotEqualTo(fingerprints.cancellationOf(changed));
  }

  @Test
  void preservesLegacyPolicyFingerprintsAndIncludesTypedConditionChanges() {
    var legacy = new RecordCompletenessQuery("model-a", "app", "form", SCHEMA, List.of("title"), null);
    var original = new RecordWritePolicyPublicationCommand("model-a", "app", "form", SCHEMA, 0, 1,
        "publish", "b".repeat(64), Map.of("required", legacy), 1000);
    // Pinned from the original v1 shape, before the optional condition property existed.
    assertThat(fingerprints.of(original)).isEqualTo("017281a2afc58d5068526609ee6f49e78ce55a3f7f17c935f54fc9587173036a");
    var structural = new RecordCompletenessQuery("model-a", "app", "form", SCHEMA, List.of("title"), null,
        null, RecordJsonSemantics.STRUCTURAL_V1);
    assertThat(fingerprints.of(new RecordWritePolicyPublicationCommand("model-a", "app", "form", SCHEMA, 0, 1,
        "publish", "b".repeat(64), Map.of("required", structural), 1000))).isNotEqualTo(fingerprints.of(original));
    var changed = new RecordCompletenessQuery("model-a", "app", "form", SCHEMA, List.of("title"), null,
        new RecordValueCondition(RecordValueConditionOperator.FALSE, null, List.of(), List.of()));
    assertThat(fingerprints.of(new RecordWritePolicyPublicationCommand("model-a", "app", "form", SCHEMA, 0, 1,
        "publish", "b".repeat(64), Map.of("required", changed), 1000))).isNotEqualTo(fingerprints.of(original));
    var valueCheck = new RecordViolationQuery("model-a", "app", "form", SCHEMA, null,
        new RecordValueCondition(RecordValueConditionOperator.FALSE, null, List.of(), List.of()), RecordJsonSemantics.STRUCTURAL_V1);
    assertThat(fingerprints.of(new RecordWritePolicyPublicationCommand("model-a", "app", "form", SCHEMA, 0, 1,
        "publish", "b".repeat(64), original.checks(), 1000, Map.of("choices", valueCheck)))).isNotEqualTo(fingerprints.of(original));
  }

  private static RecordCreateCommand command(final Map<String, Object> values) {
    return new RecordCreateCommand("model-a", SCHEMA, "request-1", values);
  }
}
