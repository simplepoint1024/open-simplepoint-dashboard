package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope;

class RelatedRecordFingerprintTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final RecordRequestFingerprint fingerprints = new RecordRequestFingerprint(mapper);
  private static final String TARGET = "aaaaaaaa-1111-2222-3333-000000000001";

  @Test
  void scopeCannotBeStrippedOrRetargetedUnderTheSameDurableIntent() throws Exception {
    var aggregate = aggregate(30_000, 1);
    var scope =
        new RelatedRecordScope(
            "1", "customer", "customer-model", TARGET, RelatedRecordScope.Mode.MAINTAIN, null);
    var related = new RelatedRecordCommand(aggregate, scope);
    String hash = fingerprints.of(related);
    assertThat(hash).isNotEqualTo(fingerprints.of(aggregate));
    assertThat(fingerprints.of(new RelatedRecordCommand(aggregate(60_000, 2), scope)))
        .isEqualTo(hash);
    assertThat(mapper.readValue(mapper.writeValueAsBytes(related), RelatedRecordCommand.class))
        .isEqualTo(related);
    assertThat(mapper.writeValueAsString(aggregate)).doesNotContain("scope", "related", "snapshot");
    for (var changed :
        List.of(
            new RelatedRecordScope(
                "1", "other", "customer-model", TARGET, RelatedRecordScope.Mode.MAINTAIN, null),
            new RelatedRecordScope(
                "1", "customer", "other-model", TARGET, RelatedRecordScope.Mode.MAINTAIN, null),
            new RelatedRecordScope(
                "1",
                "customer",
                "customer-model",
                "aaaaaaaa-1111-2222-3333-000000000002",
                RelatedRecordScope.Mode.MAINTAIN,
                null))) {
      assertThat(fingerprints.of(new RelatedRecordCommand(aggregate, changed))).isNotEqualTo(hash);
    }
  }

  @Test
  void rejectsInvalidVersionsAndUnlinkCreatesRatherThanTreatingThemAsOrdinaryWrites() {
    assertThatThrownBy(
            () ->
                new RelatedRecordScope(
                    "2", "customer", "model", TARGET, RelatedRecordScope.Mode.MAINTAIN, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RelatedRecordScope(
                    "1", "customer", "model", "1-1-1-1-1", RelatedRecordScope.Mode.MAINTAIN, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RelatedRecordScope(
                    "1", "customer", "model", TARGET, RelatedRecordScope.Mode.MAINTAIN, TARGET))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RelatedRecordCommand(
                    aggregate(30_000, 1),
                    new RelatedRecordScope(
                        "1", "customer", "model", TARGET, RelatedRecordScope.Mode.UNLINK, null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static OwnedRecordCommand aggregate(int timeout, long policy) {
    return new OwnedRecordCommand(
        new RecordTransactionCommand(
            "source",
            new RecordSchemaReference(1, "a".repeat(64)),
            List.of(new RecordCreateMutation("create", Map.of("customer_id", TARGET))),
            timeout,
            "same-key",
            policy),
        List.of());
  }
}
