package org.simplepoint.plugin.ai.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.model.AiDependencyKind;
import org.simplepoint.plugin.ai.core.api.model.AiDependencyOption;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;

/** Locks the dependency directory DTO to its non-sensitive wire contract. */
class AiDependencyOptionContractTest {

  @Test
  void exposesOnlyMinimalDependencyLabelFields() throws Exception {
    assertThat(Arrays.stream(AiDependencyOption.class.getRecordComponents())
        .map(component -> component.getName())
        .toList()).containsExactly(
            "kind",
            "resourceId",
            "resourceCode",
            "resourceName",
            "resourceVersionId",
            "resourceVersion",
            "scopeType",
            "publishedAt",
            "selectable",
            "availabilityCode"
    );

    JsonNode json = new ObjectMapper().valueToTree(new AiDependencyOption(
        AiDependencyKind.SKILL,
        "skill-a",
        "summarize",
        "Summarize",
        "skill-version-a",
        "1.0.0",
        AiResourceScope.SYSTEM,
        null,
        true,
        null
    ));

    assertThat(StreamSupport.stream(json.spliterator(), false).count())
        .isEqualTo(10);
    assertThat(json.has("tenantId")).isFalse();
    assertThat(json.has("contentHash")).isFalse();
    assertThat(json.has("manifest")).isFalse();
    assertThat(json.has("artifactReference")).isFalse();
  }
}
