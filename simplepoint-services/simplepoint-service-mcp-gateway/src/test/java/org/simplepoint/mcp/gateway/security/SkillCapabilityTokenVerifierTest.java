package org.simplepoint.mcp.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.mcp.gateway.config.McpGatewayProperties;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayConnection;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayPromptGetRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayPromptGetResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayResourceReadRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayResourceReadResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayToolCallRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayToolCallResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayWorkflowPromptGetRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayWorkflowResourceReadRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayWorkflowToolCallRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpSkillCapabilityClaims;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpSkillCapabilityTokenCodec;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class SkillCapabilityTokenVerifierTest {

  private static final String SIGNING_KEY =
      "test-capability-signing-key-with-at-least-32-bytes";

  @Mock
  private StringRedisTemplate redisTemplate;

  @Mock
  private ValueOperations<String, String> valueOperations;

  private ObjectMapper objectMapper;

  private SkillCapabilityTokenVerifier verifier;

  @BeforeEach
  void setUp() {
    McpGatewayProperties properties = new McpGatewayProperties();
    properties.setCapabilityTokenSigningKey(SIGNING_KEY);
    properties.setCapabilityTokenClockSkew(Duration.ZERO);
    objectMapper = new ObjectMapper();
    verifier = new SkillCapabilityTokenVerifier(
        properties,
        redisTemplate,
        objectMapper
    );
  }

  @Test
  void verifiesExactBindingAndAtomicallyConsumesNonce() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.setIfAbsent(
        anyString(),
        eq("1"),
        any(Duration.class)
    )).thenReturn(true);
    McpSkillCapabilityClaims claims = claims("echo");

    McpSkillCapabilityClaims verified = verifier.verifyAndConsume(
        request(claims, "echo")
    );

    assertThat(verified).isEqualTo(claims);
    verify(valueOperations).setIfAbsent(
        anyString(),
        eq("1"),
        any(Duration.class)
    );
    verifier.validateResult(
        claims,
        new McpGatewayToolCallResult(
            List.of(Map.of("type", "text", "text", "ok")),
            false,
            Map.of("message", "ok"),
            Map.of()
        )
    );
  }

  @Test
  void rejectsRequestThatDoesNotMatchSignedTool() {
    McpSkillCapabilityClaims claims = claims("echo");

    assertThatThrownBy(() -> verifier.verifyAndConsume(
        request(claims, "other")
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match");
    verifyNoInteractions(redisTemplate);
  }

  @Test
  void rejectsReplayAcrossGatewayInstances() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.setIfAbsent(
        anyString(),
        eq("1"),
        any(Duration.class)
    )).thenReturn(false);
    McpSkillCapabilityClaims claims = claims("echo");

    assertThatThrownBy(() -> verifier.verifyAndConsume(
        request(claims, "echo")
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("already consumed");
  }

  @Test
  void verifiesPromptOperationTargetAndResultBudget() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.setIfAbsent(
        anyString(),
        eq("1"),
        any(Duration.class)
    )).thenReturn(true);
    McpSkillCapabilityClaims claims = claims("prompts/get", "welcome");
    McpGatewayPromptGetRequest call = new McpGatewayPromptGetRequest(
        connection(),
        "welcome",
        Map.of("name", "SimplePoint"),
        metadata(),
        "execution-a:step-a"
    );
    McpSkillCapabilityClaims verified = verifier.verifyAndConsume(
        new McpGatewayWorkflowPromptGetRequest(call, token(claims))
    );

    assertThat(verified).isEqualTo(claims);
    verifier.validateResult(
        claims,
        new McpGatewayPromptGetResult(
            "Welcome",
            List.of(Map.of(
                "role", "user",
                "content", Map.of("type", "text", "text", "Hello")
            )),
            Map.of()
        )
    );
  }

  @Test
  void verifiesResourceOperationAndRejectsUriOutsideToken() {
    McpSkillCapabilityClaims claims =
        claims("resources/read", "document://42");
    McpGatewayResourceReadRequest call = new McpGatewayResourceReadRequest(
        connection(),
        "document://43",
        metadata(),
        "execution-a:step-a"
    );

    assertThatThrownBy(() -> verifier.verifyAndConsume(
        new McpGatewayWorkflowResourceReadRequest(call, token(claims))
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match");
    verifyNoInteractions(redisTemplate);
  }

  private McpGatewayWorkflowToolCallRequest request(
      final McpSkillCapabilityClaims claims,
      final String toolName
  ) {
    Map<String, Object> meta = Map.of(
        "simplepoint/scopeType", metadata().get("simplepoint/scopeType"),
        "simplepoint/skillId", metadata().get("simplepoint/skillId"),
        "simplepoint/skillVersionId",
        metadata().get("simplepoint/skillVersionId"),
        "simplepoint/skillExecutionId",
        metadata().get("simplepoint/skillExecutionId"),
        "simplepoint/skillStepId", metadata().get("simplepoint/skillStepId"),
        "simplepoint/subjectId", metadata().get("simplepoint/subjectId"),
        "simplepoint/capabilitySnapshotId",
        metadata().get("simplepoint/capabilitySnapshotId")
    );
    McpGatewayToolCallRequest call = new McpGatewayToolCallRequest(
        connection(),
        toolName,
        Map.of("message", "hello"),
        meta,
        "execution-a:step-a"
    );
    return new McpGatewayWorkflowToolCallRequest(call, token(claims));
  }

  private static McpSkillCapabilityClaims claims(final String toolName) {
    return claims("tools/call", toolName);
  }

  private static McpSkillCapabilityClaims claims(
      final String operation,
      final String target
  ) {
    long now = Instant.now().getEpochSecond();
    return new McpSkillCapabilityClaims(
        "simplepoint-ai-control-plane",
        "simplepoint-mcp-gateway",
        "nonce-a",
        now,
        now + 90,
        operation,
        "SYSTEM",
        null,
        "user-a",
        null,
        "skill-a",
        "version-a",
        "execution-a",
        "step-a",
        "server-a",
        "snapshot-a",
        target,
        1,
        262144,
        262144
    );
  }

  private McpGatewayConnection connection() {
    return new McpGatewayConnection(
        "server-a",
        "https://mcp.example.com/mcp",
        null,
        false
    );
  }

  private Map<String, Object> metadata() {
    return Map.of(
        "simplepoint/scopeType", "SYSTEM",
        "simplepoint/skillId", "skill-a",
        "simplepoint/skillVersionId", "version-a",
        "simplepoint/skillExecutionId", "execution-a",
        "simplepoint/skillStepId", "step-a",
        "simplepoint/subjectId", "user-a",
        "simplepoint/capabilitySnapshotId", "snapshot-a"
    );
  }

  private String token(final McpSkillCapabilityClaims claims) {
    return McpSkillCapabilityTokenCodec.issue(
        objectMapper,
        claims,
        SIGNING_KEY
    );
  }
}
