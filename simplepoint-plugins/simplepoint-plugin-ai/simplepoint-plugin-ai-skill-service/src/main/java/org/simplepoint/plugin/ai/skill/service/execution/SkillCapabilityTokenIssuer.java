package org.simplepoint.plugin.ai.skill.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpSkillCapabilityClaims;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpSkillCapabilityTokenCodec;
import org.simplepoint.plugin.ai.skill.api.properties.SkillExecutionProperties;
import org.simplepoint.plugin.ai.skill.service.execution.AiSkillExecutionCoordinator.ExecutionTask;
import org.simplepoint.plugin.ai.skill.service.execution.AiSkillExecutionCoordinator.StepTask;
import org.springframework.stereotype.Service;

/**
 * Issues short-lived, one-call capabilities for exact Skill MCP steps.
 */
@Service
public class SkillCapabilityTokenIssuer {

  static final String TOOL_CALL_OPERATION = "tools/call";

  static final String PROMPT_GET_OPERATION = "prompts/get";

  static final String RESOURCE_READ_OPERATION = "resources/read";

  private final SkillExecutionProperties properties;

  private final ObjectMapper objectMapper;

  /**
   * Creates the capability issuer.
   */
  public SkillCapabilityTokenIssuer(
      final SkillExecutionProperties properties,
      final ObjectMapper objectMapper
  ) {
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  /**
   * Issues a token whose lifetime never exceeds the execution deadline.
   */
  public IssuedCapability issue(
      final ExecutionTask task,
      final StepTask step,
      final String target
  ) {
    Instant now = Instant.now();
    Instant expiresAt = now.plus(tokenTtl());
    if (task.deadlineAt() != null && task.deadlineAt().isBefore(expiresAt)) {
      expiresAt = task.deadlineAt();
    }
    if (!expiresAt.isAfter(now)
        || expiresAt.getEpochSecond() <= now.getEpochSecond()) {
      throw new SkillExecutionBudgetExceededException(
          "SKILL_BUDGET_TIME_EXCEEDED",
          "Skill execution time budget was exhausted"
      );
    }
    String tokenId = UUID.randomUUID().toString();
    long maximumPayload = Math.min(
        perPayloadMaximum(),
        task.maximumPayloadBytes()
    );
    McpSkillCapabilityClaims claims = new McpSkillCapabilityClaims(
        issuer(),
        audience(),
        tokenId,
        now.getEpochSecond(),
        expiresAt.getEpochSecond(),
        operation(step),
        task.scopeType().name(),
        task.tenantId(),
        task.requestedBy(),
        null,
        task.skillId(),
        task.skillVersionId(),
        task.executionId(),
        step.stepId(),
        step.serverId(),
        step.snapshotId(),
        requiredTarget(target),
        1,
        maximumPayload,
        maximumPayload
    );
    String token = McpSkillCapabilityTokenCodec.issue(
        objectMapper,
        claims,
        properties.getCapabilityTokenSigningKey()
    );
    return new IssuedCapability(token, sha256(tokenId));
  }

  private static String operation(final StepTask step) {
    return switch (step.stepType()) {
      case "tool" -> TOOL_CALL_OPERATION;
      case "prompt" -> PROMPT_GET_OPERATION;
      case "resource" -> RESOURCE_READ_OPERATION;
      default -> throw new IllegalArgumentException(
          "Unsupported Skill MCP step type: " + step.stepType()
      );
    };
  }

  private static String requiredTarget(final String value) {
    if (value == null || value.isBlank() || value.length() > 2048) {
      throw new IllegalArgumentException(
          "Skill capability target is invalid"
      );
    }
    return value.trim();
  }

  private Duration tokenTtl() {
    Duration configured = properties.getCapabilityTokenTtl();
    if (configured == null || configured.compareTo(Duration.ofSeconds(10)) < 0) {
      return Duration.ofSeconds(90);
    }
    return configured.compareTo(Duration.ofMinutes(5)) > 0
        ? Duration.ofMinutes(5) : configured;
  }

  private long perPayloadMaximum() {
    Integer configured = properties.getMaximumPayloadBytes();
    return configured == null ? 256L * 1024L : Math.max(1024L, configured);
  }

  private String issuer() {
    String value = properties.getCapabilityTokenIssuer();
    return value == null || value.isBlank()
        ? "simplepoint-ai-control-plane" : value.trim();
  }

  private String audience() {
    String value = properties.getCapabilityTokenAudience();
    return value == null || value.isBlank()
        ? "simplepoint-mcp-gateway" : value.trim();
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

  /**
   * Token material returned to the executor. Only the nonce hash is persisted.
   */
  public record IssuedCapability(
      String token,
      String tokenIdHash
  ) {
  }
}
