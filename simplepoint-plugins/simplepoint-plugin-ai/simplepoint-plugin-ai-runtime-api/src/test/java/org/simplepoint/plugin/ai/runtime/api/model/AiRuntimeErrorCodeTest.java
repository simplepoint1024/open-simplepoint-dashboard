package org.simplepoint.plugin.ai.runtime.api.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeNode;

class AiRuntimeErrorCodeTest {

  @Test
  void classifiesKnownOperationalDiagnostics() {
    assertThat(AiRuntimeErrorCode.fromDiagnostic(
        "Runtime node heartbeat lease expired"
    )).isEqualTo(AiRuntimeErrorCode.AI_RUNTIME_NODE_HEARTBEAT_EXPIRED);
    assertThat(AiRuntimeErrorCode.fromDiagnostic(
        "Runtime workload execution deadline expired"
    )).isEqualTo(AiRuntimeErrorCode.AI_RUNTIME_WORKLOAD_DEADLINE_EXPIRED);
  }

  @Test
  void serializationNeverExposesUnknownInternalProse() {
    String privateProse = "container endpoint and registry credential details";
    AiRuntimeNode node = new AiRuntimeNode();
    node.setLastError(privateProse);

    JsonNode json = new ObjectMapper().valueToTree(node);

    assertThat(json.path("lastError").asText())
        .isEqualTo(AiRuntimeErrorCode.AI_RUNTIME_OPERATION_FAILED.name());
    assertThat(json.toString()).doesNotContain(privateProse);
  }
}
