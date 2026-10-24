package org.simplepoint.plugin.dna.runtime.api.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordWritePolicyProgressTest {
  private final String fingerprint = "a".repeat(64);
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void legacyResponsesKeepTheirJsonShape() throws Exception {
    var publication = new RecordWritePolicyPublicationView(true, 1, fingerprint, Map.of());
    var cancellation = new RecordWritePolicyCancellationView(true, 1, fingerprint, "b".repeat(64));
    assertThat(mapper.writeValueAsString(publication)).doesNotContain("progress");
    assertThat(mapper.writeValueAsString(cancellation)).doesNotContain("progress");
    assertThat(mapper.readValue(mapper.writeValueAsString(publication), RecordWritePolicyPublicationView.class)).isEqualTo(publication);
    assertThat(mapper.readValue(mapper.writeValueAsString(cancellation), RecordWritePolicyCancellationView.class)).isEqualTo(cancellation);
  }

  @Test
  void completeBatchCountsRemainNonterminalUntilTheTargetCommits() throws Exception {
    var progress = new RecordWritePolicyProgress(1, RecordWritePolicyPhase.APPLYING, 7, 10, 10, null);
    var response = new RecordWritePolicyPublicationView(false, 0, fingerprint, Map.of(), Map.of(), progress);
    assertThat(mapper.readValue(mapper.writeValueAsString(response), RecordWritePolicyPublicationView.class)).isEqualTo(response);
    assertThat(response.published()).isFalse();
    assertThatThrownBy(() -> new RecordWritePolicyPublicationView(true, 1, fingerprint, Map.of(), Map.of(), progress))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordWritePolicyPublicationView(false, 0, fingerprint, Map.of(), Map.of("invalid", 1L), progress))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void finalInspectionCarriesDurableSequenceWithoutClaimingActivation() throws Exception {
    var code = org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode.RECORD_WRITE_POLICY_PRECHECK_FAILED;
    var progress = new RecordWritePolicyProgress(1, RecordWritePolicyPhase.APPLYING, 8, 10, 10, code);
    var response = new RecordWritePolicyPublicationView(false, 0, fingerprint, Map.of(), Map.of("rule", 2L), progress);
    assertThat(mapper.readValue(mapper.writeValueAsString(response), RecordWritePolicyPublicationView.class)).isEqualTo(response);
    for (var invalid : java.util.List.of(new RecordWritePolicyProgress(1, RecordWritePolicyPhase.STAGING, 8, 10, 10, code),
        new RecordWritePolicyProgress(1, RecordWritePolicyPhase.APPLYING, 8, 9, 10, code),
        new RecordWritePolicyProgress(1, RecordWritePolicyPhase.APPLYING, 8, 10, 10,
            org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED))) {
      assertThatThrownBy(() -> new RecordWritePolicyPublicationView(false, 0, fingerprint, Map.of(), Map.of("rule", 2L), invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void restorationNeverClaimsThatTheTargetVersionWasConsumed() throws Exception {
    var restoring = new RecordWritePolicyProgress(1, RecordWritePolicyPhase.RESTORING, 8, 0, 10, null);
    var response = new RecordWritePolicyCancellationView(false, 0, fingerprint, null, restoring);
    assertThat(mapper.readValue(mapper.writeValueAsString(response), RecordWritePolicyCancellationView.class)).isEqualTo(response);
    assertThatThrownBy(() -> new RecordWritePolicyCancellationView(true, 1, fingerprint, "b".repeat(64), restoring))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordWritePolicyCancellationView(false, 1, fingerprint, fingerprint, restoring))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordWritePolicyPublicationView(false, 0, fingerprint, Map.of(), Map.of(), restoring))
        .isInstanceOf(IllegalArgumentException.class);
    var applying = new RecordWritePolicyProgress(1, RecordWritePolicyPhase.APPLYING, 7, 0, 10, null);
    assertThatThrownBy(() -> new RecordWritePolicyCancellationView(false, 0, fingerprint, null, applying))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsInvalidCountersAndFutureProgressContracts() {
    assertThatThrownBy(() -> new RecordWritePolicyProgress(2, RecordWritePolicyPhase.STAGING, 1, 0, 0, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordWritePolicyProgress(1, RecordWritePolicyPhase.STAGING, 0, 0, 0, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordWritePolicyProgress(1, RecordWritePolicyPhase.STAGING, 1, -1, 1, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordWritePolicyProgress(1, RecordWritePolicyPhase.STAGING, 1, 2, 1, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
