package org.simplepoint.plugin.ai.skill.service.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionApprovalPolicy;
import org.springframework.stereotype.Component;

/**
 * Validates and reads immutable execution approval policies.
 */
@Component
public class SkillApprovalPolicy {

  private static final TypeReference<Map<String, Object>> MAP_TYPE =
      new TypeReference<>() {
      };

  private static final Set<String> APPROVAL_KEYS = Set.of("execution");

  private static final Set<String> EXECUTION_KEYS = Set.of(
      "required",
      "allowSelfApproval",
      "instructions"
  );

  private final ObjectMapper objectMapper;

  /**
   * Creates the approval policy reader.
   */
  public SkillApprovalPolicy(final ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * Normalizes the Manifest {@code spec.approvals} object.
   */
  public SkillExecutionApprovalPolicy normalize(final Object source) {
    Map<?, ?> approvals = object(source, "Skill approvals");
    assertAllowedKeys(approvals, APPROVAL_KEYS, "Skill approvals");
    Map<?, ?> execution = object(
        approvals.get("execution"),
        "Skill execution approval"
    );
    assertAllowedKeys(
        execution,
        EXECUTION_KEYS,
        "Skill execution approval"
    );
    boolean required = bool(
        execution.get("required"),
        false,
        "Skill execution approval required"
    );
    boolean allowSelfApproval = bool(
        execution.get("allowSelfApproval"),
        false,
        "Skill execution approval allowSelfApproval"
    );
    String instructions = text(
        execution.get("instructions"),
        "Skill execution approval instructions"
    );
    if (!required && allowSelfApproval) {
      throw new IllegalArgumentException(
          "Skill self approval can only be enabled when approval is required"
      );
    }
    return new SkillExecutionApprovalPolicy(
        required,
        required && allowSelfApproval,
        instructions
    );
  }

  /**
   * Reads the approval policy from a stored immutable Manifest.
   */
  public SkillExecutionApprovalPolicy readManifest(final String manifestJson) {
    if (manifestJson == null || manifestJson.isBlank()) {
      return normalize(null);
    }
    try {
      Map<String, Object> manifest = objectMapper.readValue(
          manifestJson,
          MAP_TYPE
      );
      Object specValue = manifest.get("spec");
      if (!(specValue instanceof Map<?, ?> spec)) {
        throw new IllegalStateException("Stored Skill manifest spec is missing");
      }
      return normalize(spec.get("approvals"));
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Stored Skill approval policy is corrupted", ex);
    }
  }

  private static Map<?, ?> object(final Object source, final String label) {
    if (source == null) {
      return Map.of();
    }
    if (source instanceof Map<?, ?> map) {
      return map;
    }
    throw new IllegalArgumentException(label + " must be an object");
  }

  private static void assertAllowedKeys(
      final Map<?, ?> source,
      final Set<String> allowed,
      final String label
  ) {
    for (Object key : source.keySet()) {
      if (!(key instanceof String name) || !allowed.contains(name)) {
        throw new IllegalArgumentException(
            label + " contains unsupported field " + key
        );
      }
    }
  }

  private static boolean bool(
      final Object value,
      final boolean fallback,
      final String label
  ) {
    if (value == null) {
      return fallback;
    }
    if (value instanceof Boolean bool) {
      return bool;
    }
    throw new IllegalArgumentException(label + " must be a boolean");
  }

  private static String text(final Object value, final String label) {
    if (value == null) {
      return null;
    }
    if (!(value instanceof String text)) {
      throw new IllegalArgumentException(label + " must be a string");
    }
    String normalized = text.trim();
    if (normalized.isEmpty()) {
      return null;
    }
    if (normalized.length() > 512) {
      throw new IllegalArgumentException(label + " is too long");
    }
    return normalized;
  }
}
