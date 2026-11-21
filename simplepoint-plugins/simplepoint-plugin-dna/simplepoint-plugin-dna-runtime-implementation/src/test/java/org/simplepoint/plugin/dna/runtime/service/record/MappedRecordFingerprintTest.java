package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.record.MappedOwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMappingConfirmation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMappingObservation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope;

class MappedRecordFingerprintTest {
  private static final String RECORD = "11111111-1111-1111-1111-111111111111";
  private static final String REVISION = "a".repeat(64);
  private final RecordRequestFingerprint fingerprints =
      new RecordRequestFingerprint(new ObjectMapper());

  @Test
  void mappedIntentCannotReplayAnOrdinaryOrInverseReceipt() {
    var ordinary = aggregate(1000, 0);
    var scope =
        new RelatedRecordScope(
            "1", "customer", "customers", RECORD, RelatedRecordScope.Mode.MAINTAIN, null);
    var mapped = new MappedOwnedRecordCommand("1", ordinary, scope, List.of());
    assertThat(fingerprints.of(mapped))
        .isNotEqualTo(fingerprints.of(ordinary))
        .isNotEqualTo(fingerprints.of(new RelatedRecordCommand(ordinary, scope)))
        .isNotEqualTo(
            fingerprints.of(new MappedOwnedRecordCommand("1", ordinary, null, List.of())));
  }

  @Test
  void retriesKeepIntentWhileObservationAndExplicitDefaultChoicesChangeIt() {
    var selected = confirmation("customer", REVISION, 1, List.of("tax", "price"));
    var base = new MappedOwnedRecordCommand("1", aggregate(1000, 0), null, List.of(selected));
    var retry =
        new MappedOwnedRecordCommand(
            "1",
            aggregate(2000, 2),
            null,
            List.of(confirmation("customer", REVISION, 1, List.of("price", "tax"))));
    assertThat(fingerprints.of(base)).isEqualTo(fingerprints.of(retry));
    for (var changed :
        List.of(
            confirmation("customer", REVISION, 2, selected.applyDefaultMappingIds()),
            confirmation("customer", "b".repeat(64), 1, selected.applyDefaultMappingIds()),
            confirmation("other", REVISION, 1, selected.applyDefaultMappingIds()),
            confirmation("customer", REVISION, 1, List.of("price")),
            confirmation("customer", REVISION, 1, List.of()))) {
      assertThat(
              fingerprints.of(
                  new MappedOwnedRecordCommand("1", base.command(), null, List.of(changed))))
          .isNotEqualTo(fingerprints.of(base));
    }
  }

  @Test
  void confirmationOrderingIsNotIntentButItsSourceIdentityIs() {
    var first = confirmation("customer", REVISION, 1, List.of());
    var second = confirmation("supplier", REVISION, 1, List.of());
    var ordinary = aggregate(1000, 0);
    assertThat(
            fingerprints.of(
                new MappedOwnedRecordCommand("1", ordinary, null, List.of(first, second))))
        .isEqualTo(
            fingerprints.of(
                new MappedOwnedRecordCommand("1", ordinary, null, List.of(second, first))));
  }

  private static RecordReferenceMappingConfirmation confirmation(
      String relation, String revision, long version, List<String> defaults) {
    return new RecordReferenceMappingConfirmation(
        relation,
        null,
        null,
        null,
        new RecordReferenceMappingObservation(revision, RECORD, version),
        defaults);
  }

  private static OwnedRecordCommand aggregate(int timeout, long policy) {
    return new OwnedRecordCommand(
        new RecordTransactionCommand(
            "model",
            new RecordSchemaReference(1, REVISION),
            List.of(new RecordCreateMutation("parent", Map.of("title", "Trade"))),
            timeout,
            "create",
            policy),
        List.of());
  }
}
