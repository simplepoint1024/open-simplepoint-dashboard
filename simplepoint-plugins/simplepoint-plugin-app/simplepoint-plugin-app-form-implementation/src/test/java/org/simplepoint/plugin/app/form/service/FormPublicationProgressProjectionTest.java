package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormReleaseView;
import org.simplepoint.plugin.app.form.entity.FormWritePolicyPublicationStatus;
import org.simplepoint.plugin.app.form.service.FormWritePolicyPublicationStateService.PreparedPolicy;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPhase;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyProgress;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationView;

class FormPublicationProgressProjectionTest {
  @Test
  void exactCountersDoNotChangeLegacyResponsesOrExposeTargetMetadata() throws Exception {
    var mapper = new ObjectMapper();
    assertThat(mapper.writeValueAsString(new ApplicationFormReleaseView(null, null)))
        .isEqualTo("{\"dataPublication\":null,\"pageBinding\":null}");
    var target = new RecordWritePolicyPublicationView(false, 9, "a".repeat(64), Map.of(), Map.of(),
        new RecordWritePolicyProgress(1, RecordWritePolicyPhase.STAGING, Long.MAX_VALUE,
            9007199254740993L, Long.MAX_VALUE, null));
    var projected = FormPublicationProgressProjection.project(new PreparedPolicy("internal-policy",
        FormWritePolicyPublicationStatus.PREPARED, null, "internal-fingerprint", target));
    var json = mapper.readTree(mapper.writeValueAsString(new ApplicationFormReleaseView(null, null, null, projected)));
    assertThat(json.path("progress").path("completedRecords").textValue()).isEqualTo("9007199254740993");
    assertThat(json.path("progress").path("sequence").textValue()).isEqualTo(Long.toString(Long.MAX_VALUE));
    assertThat(json.path("progress").size()).isEqualTo(5);
    assertThat(json.toString()).doesNotContain("internal", "fingerprint", "published", "command");
  }
}
