package org.simplepoint.mcp.gateway.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import org.simplepoint.mcp.gateway.config.McpGatewayProperties;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayConnection;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayPromptGetRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayResourceReadRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayToolCallRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayWorkflowPromptGetRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayWorkflowResourceReadRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayWorkflowToolCallRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpSkillCapabilityClaims;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpSkillCapabilityTokenCodec;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Fail-closed validation and single-use consumption of Skill capabilities.
 */
@Service
public class SkillCapabilityTokenVerifier {

  private static final String TOOL_CALL_OPERATION = "tools/call";

  private static final String PROMPT_GET_OPERATION = "prompts/get";

  private static final String RESOURCE_READ_OPERATION = "resources/read";

  private final McpGatewayProperties properties;

  private final StringRedisTemplate redisTemplate;

  private final ObjectMapper objectMapper;

  /**
   * Creates the distributed capability verifier.
   */
  public SkillCapabilityTokenVerifier(
      final McpGatewayProperties properties,
      final StringRedisTemplate redisTemplate,
      final ObjectMapper objectMapper
  ) {
    this.properties = properties;
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
  }

  /**
   * Verifies and consumes one exact Tool capability.
   */
  public McpSkillCapabilityClaims verifyAndConsume(
      final McpGatewayWorkflowToolCallRequest request
  ) {
    if (request == null || request.call() == null) {
      throw new IllegalArgumentException(
          "MCP workflow Tool request must not be null"
      );
    }
    McpGatewayToolCallRequest call = request.call();
    return verifyAndConsume(
        request.capabilityToken(),
        TOOL_CALL_OPERATION,
        call.connection(),
        call.toolName(),
        call.meta(),
        call.operationId(),
        call.arguments() == null ? Map.of() : call.arguments()
    );
  }

  /**
   * Verifies and consumes one exact Prompt capability.
   */
  public McpSkillCapabilityClaims verifyAndConsume(
      final McpGatewayWorkflowPromptGetRequest request
  ) {
    if (request == null || request.call() == null) {
      throw new IllegalArgumentException(
          "MCP workflow Prompt request must not be null"
      );
    }
    McpGatewayPromptGetRequest call = request.call();
    return verifyAndConsume(
        request.capabilityToken(),
        PROMPT_GET_OPERATION,
        call.connection(),
        call.name(),
        call.meta(),
        call.operationId(),
        call.arguments() == null ? Map.of() : call.arguments()
    );
  }

  /**
   * Verifies and consumes one exact Resource capability.
   */
  public McpSkillCapabilityClaims verifyAndConsume(
      final McpGatewayWorkflowResourceReadRequest request
  ) {
    if (request == null || request.call() == null) {
      throw new IllegalArgumentException(
          "MCP workflow Resource request must not be null"
      );
    }
    McpGatewayResourceReadRequest call = request.call();
    return verifyAndConsume(
        request.capabilityToken(),
        RESOURCE_READ_OPERATION,
        call.connection(),
        call.uri(),
        call.meta(),
        call.operationId(),
        Map.of("uri", call.uri())
    );
  }

  private McpSkillCapabilityClaims verifyAndConsume(
      final String token,
      final String operation,
      final McpGatewayConnection connection,
      final String target,
      final Map<String, Object> meta,
      final String operationId,
      final Object payload
  ) {
    McpSkillCapabilityClaims claims = McpSkillCapabilityTokenCodec.verify(
        objectMapper,
        token,
        properties.getCapabilityTokenSigningKey()
    );
    validateClaims(claims, operation);
    validateBinding(
        claims,
        connection,
        target,
        meta,
        operationId
    );
    assertSerializedSize(
        payload,
        claims.maximumRequestBytes(),
        "Skill capability request budget"
    );
    consume(claims);
    return claims;
  }

  /**
   * Applies the token result budget before returning data to the control plane.
   */
  public void validateResult(
      final McpSkillCapabilityClaims claims,
      final Object result
  ) {
    assertSerializedSize(
        result,
        claims.maximumResultBytes(),
        "Skill capability result budget"
    );
  }

  private void validateClaims(
      final McpSkillCapabilityClaims claims,
      final String operation
  ) {
    if (claims == null
        || !expected(properties.getCapabilityTokenIssuer())
            .equals(claims.issuer())
        || !expected(properties.getCapabilityTokenAudience())
            .equals(claims.audience())
        || !operation.equals(claims.operation())
        || blank(claims.tokenId())
        || blank(claims.scopeType())
        || blank(claims.skillId())
        || blank(claims.skillVersionId())
        || blank(claims.executionId())
        || blank(claims.stepId())
        || blank(claims.serverId())
        || blank(claims.snapshotId())
        || blank(claims.target())
        || claims.maximumCalls() != 1
        || claims.maximumRequestBytes() < 1
        || claims.maximumResultBytes() < 1) {
      throw invalid();
    }
    if (!"SYSTEM".equals(claims.scopeType())
        && !"TENANT".equals(claims.scopeType())
        || "SYSTEM".equals(claims.scopeType()) && claims.tenantId() != null
        || "TENANT".equals(claims.scopeType()) && blank(claims.tenantId())) {
      throw invalid();
    }
    long now = Instant.now().getEpochSecond();
    long skew = clockSkew().toSeconds();
    if (claims.issuedAtEpochSecond() > now + skew
        || claims.expiresAtEpochSecond() <= now - skew
        || claims.expiresAtEpochSecond() <= claims.issuedAtEpochSecond()
        || claims.expiresAtEpochSecond() - claims.issuedAtEpochSecond()
            > maximumTtl().toSeconds()) {
      throw invalid();
    }
  }

  private static void validateBinding(
      final McpSkillCapabilityClaims claims,
      final McpGatewayConnection connection,
      final String target,
      final Map<String, Object> requestMeta,
      final String operationId
  ) {
    if (connection == null
        || !Objects.equals(
            claims.serverId(),
            connection.connectionId()
        )
        || !Objects.equals(claims.target(), target)
        || !Objects.equals(
            claims.executionId() + ":" + claims.stepId(),
            operationId
        )) {
      throw invalid();
    }
    Map<String, Object> meta = requestMeta == null ? Map.of() : requestMeta;
    requireMeta(meta, "simplepoint/scopeType", claims.scopeType());
    requireMeta(meta, "simplepoint/tenantId", claims.tenantId());
    requireMeta(meta, "simplepoint/skillId", claims.skillId());
    requireMeta(meta, "simplepoint/skillVersionId", claims.skillVersionId());
    requireMeta(meta, "simplepoint/skillExecutionId", claims.executionId());
    requireMeta(meta, "simplepoint/skillStepId", claims.stepId());
    requireMeta(meta, "simplepoint/subjectId", claims.subjectId());
    requireMeta(
        meta,
        "simplepoint/capabilitySnapshotId",
        claims.snapshotId()
    );
  }

  private void consume(final McpSkillCapabilityClaims claims) {
    long seconds = Math.max(
        1,
        claims.expiresAtEpochSecond()
            - Instant.now().getEpochSecond()
            + clockSkew().toSeconds()
    );
    String key = replayPrefix() + sha256(claims.tokenId());
    Boolean stored = redisTemplate.opsForValue().setIfAbsent(
        key,
        "1",
        Duration.ofSeconds(seconds)
    );
    if (!Boolean.TRUE.equals(stored)) {
      throw new IllegalArgumentException(
          "Skill capability token was already consumed"
      );
    }
  }

  private void assertSerializedSize(
      final Object value,
      final long maximum,
      final String label
  ) {
    try {
      if (objectMapper.writeValueAsBytes(value).length > maximum) {
        throw new IllegalArgumentException(label + " was exceeded");
      }
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException(label + " cannot be evaluated");
    }
  }

  private Duration clockSkew() {
    Duration value = properties.getCapabilityTokenClockSkew();
    if (value == null || value.isNegative()) {
      return Duration.ofSeconds(5);
    }
    return value.compareTo(Duration.ofSeconds(30)) > 0
        ? Duration.ofSeconds(30) : value;
  }

  private Duration maximumTtl() {
    Duration value = properties.getCapabilityTokenMaximumTtl();
    if (value == null || value.compareTo(Duration.ofSeconds(10)) < 0) {
      return Duration.ofMinutes(5);
    }
    return value.compareTo(Duration.ofMinutes(10)) > 0
        ? Duration.ofMinutes(10) : value;
  }

  private String replayPrefix() {
    String value = properties.getCapabilityTokenReplayKeyPrefix();
    if (value == null || value.isBlank() || value.length() > 256) {
      throw new IllegalStateException(
          "Skill capability replay key prefix is invalid"
      );
    }
    return value.trim();
  }

  private static String expected(final String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          "Skill capability issuer or audience is not configured"
      );
    }
    return value.trim();
  }

  private static void requireMeta(
      final Map<String, Object> meta,
      final String key,
      final String expected
  ) {
    Object value = meta.get(key);
    String actual = value == null ? null : String.valueOf(value);
    if (!Objects.equals(expected, actual)) {
      throw invalid();
    }
  }

  private static boolean blank(final String value) {
    return value == null || value.isBlank();
  }

  private static String sha256(final String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256")
              .digest(value.getBytes(StandardCharsets.UTF_8))
      );
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable", ex);
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException(
        "Skill capability token is invalid or does not match the MCP request"
    );
  }
}
