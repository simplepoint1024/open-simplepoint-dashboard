package org.simplepoint.plugin.ai.skill.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpToolDescriptor;
import org.simplepoint.plugin.ai.mcp.api.model.McpCapabilitySnapshotDetails;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpServerDefinitionService;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class SkillDraftCapabilityResolverTest {

  @Mock
  private ObjectProvider<AiMcpServerDefinitionService> serviceProvider;

  @Mock
  private AiMcpServerDefinitionService service;

  @Test
  void startsWithoutMcpManagementBeanAndFailsOnlyWhenDraftResolutionIsUsed() {
    when(serviceProvider.getIfAvailable()).thenReturn(null);
    SkillDraftCapabilityResolver resolver = new SkillDraftCapabilityResolver(
        serviceProvider,
        new ObjectMapper()
    );
    Map<String, Object> spec = Map.of(
        "tools",
        List.of(Map.of(
            "alias", "echo",
            "serverId", "server-a",
            "snapshotId", "snapshot-a",
            "name", "echo"
        ))
    );

    assertThatThrownBy(() -> resolver.resolve(spec))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unavailable in this runtime");
  }

  @Test
  void resolvesToolOutputSchemaFromTheExactPinnedSnapshot() {
    Map<String, Object> outputSchema = Map.of(
        "type", "object",
        "properties", Map.of("message", Map.of("type", "string"))
    );
    McpToolDescriptor tool = new McpToolDescriptor(
        "echo",
        "Echo",
        null,
        Map.of("type", "object"),
        outputSchema,
        Map.of("readOnlyHint", true),
        List.of()
    );
    when(serviceProvider.getIfAvailable()).thenReturn(service);
    when(service.getSnapshot("server-a", "snapshot-history"))
        .thenReturn(new McpCapabilitySnapshotDetails(
            "snapshot-history",
            "server-a",
            "2025-06-18",
            "test",
            "1.0.0",
            "a".repeat(64),
            Instant.EPOCH,
            false,
            Map.of(),
            List.of(tool),
            List.of(),
            List.of(),
            List.of()
        ));
    SkillDraftCapabilityResolver resolver = new SkillDraftCapabilityResolver(
        serviceProvider,
        new ObjectMapper()
    );

    var result = resolver.resolve(Map.of(
        "tools",
        List.of(Map.of(
            "alias", "echo",
            "serverId", "server-a",
            "snapshotId", "snapshot-history",
            "name", "echo"
        ))
    ));

    assertThat(result.tools().get("echo").outputSchema())
        .isEqualTo(outputSchema);
  }
}
