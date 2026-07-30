package org.simplepoint.plugin.ai.skill.service.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionBudget;
import org.simplepoint.plugin.ai.skill.api.properties.SkillExecutionProperties;
import org.springframework.stereotype.Component;

/**
 * Normalizes and bounds immutable Skill execution budgets.
 */
@Component
public class SkillBudgetPolicy {

  private static final int MINIMUM_DURATION_SECONDS = 1;

  private static final long MINIMUM_TOTAL_PAYLOAD_BYTES = 1024;

  private static final Set<String> ALLOWED_KEYS = Set.of(
      "maximumToolCalls",
      "maximumDurationSeconds",
      "maximumPayloadBytes"
  );

  private final SkillExecutionProperties properties;

  private final ObjectMapper objectMapper;

  /**
   * Creates the budget policy.
   */
  public SkillBudgetPolicy(
      final SkillExecutionProperties properties,
      final ObjectMapper objectMapper
  ) {
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  /**
   * Normalizes a Manifest budget against platform ceilings.
   */
  public SkillExecutionBudget normalize(
      final Object source,
      final int workflowToolCalls
  ) {
    if (workflowToolCalls < 0) {
      throw new IllegalArgumentException(
          "Skill workflow Tool call count must not be negative"
      );
    }
    Map<?, ?> budget;
    if (source == null) {
      budget = Map.of();
    } else if (source instanceof Map<?, ?> map) {
      budget = map;
    } else {
      throw new IllegalArgumentException("Skill budgets must be an object");
    }
    for (Object key : budget.keySet()) {
      if (!(key instanceof String name) || !ALLOWED_KEYS.contains(name)) {
        throw new IllegalArgumentException(
            "Skill budgets contains unsupported field " + key
        );
      }
    }

    int maximumToolCalls = integer(
        budget.get("maximumToolCalls"),
        Math.max(1, workflowToolCalls),
        "maximumToolCalls"
    );
    int maximumDurationSeconds = integer(
        budget.get("maximumDurationSeconds"),
        defaultDurationSeconds(),
        "maximumDurationSeconds"
    );
    long maximumPayloadBytes = longInteger(
        budget.get("maximumPayloadBytes"),
        defaultTotalPayloadBytes(),
        "maximumPayloadBytes"
    );
    if (maximumToolCalls < workflowToolCalls
        || maximumToolCalls > maximumToolCalls()) {
      throw new IllegalArgumentException(
          "Skill maximumToolCalls must cover the worst Tool call path and not "
              + "exceed the platform limit"
      );
    }
    if (maximumDurationSeconds < MINIMUM_DURATION_SECONDS
        || maximumDurationSeconds > maximumDurationSeconds()) {
      throw new IllegalArgumentException(
          "Skill maximumDurationSeconds exceeds the platform limit"
      );
    }
    if (maximumPayloadBytes < minimumTotalPayloadBytes()
        || maximumPayloadBytes > maximumTotalPayloadBytes()) {
      throw new IllegalArgumentException(
          "Skill maximumPayloadBytes exceeds the platform limit"
      );
    }
    return new SkillExecutionBudget(
        maximumToolCalls,
        maximumDurationSeconds,
        maximumPayloadBytes
    );
  }

  /**
   * Reads a stored budget, applying safe defaults to versions created before
   * budget enforcement existed.
   */
  public SkillExecutionBudget read(
      final String json,
      final int workflowToolCalls
  ) {
    if (json == null || json.isBlank()) {
      return normalize(null, workflowToolCalls);
    }
    try {
      return normalize(
          objectMapper.readValue(json, Map.class),
          workflowToolCalls
      );
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Skill execution budget is corrupted", ex);
    }
  }

  private int defaultDurationSeconds() {
    Integer value = properties.getDefaultMaximumDurationSeconds();
    return value == null ? 300 : value;
  }

  private long defaultTotalPayloadBytes() {
    Long value = properties.getDefaultMaximumTotalPayloadBytes();
    return value == null ? 1024L * 1024L : value;
  }

  private int maximumToolCalls() {
    Integer value = properties.getMaximumToolCalls();
    return value == null ? 128 : Math.max(1, value);
  }

  private int maximumDurationSeconds() {
    Integer value = properties.getMaximumDurationSeconds();
    return value == null ? 3600 : Math.max(1, value);
  }

  private long maximumTotalPayloadBytes() {
    Long value = properties.getMaximumTotalPayloadBytes();
    return value == null ? 4L * 1024L * 1024L : Math.max(1024L, value);
  }

  private long minimumTotalPayloadBytes() {
    Integer perPayload = properties.getMaximumPayloadBytes();
    return Math.max(
        MINIMUM_TOTAL_PAYLOAD_BYTES,
        perPayload == null ? 256L * 1024L : perPayload.longValue()
    );
  }

  private static int integer(
      final Object value,
      final int fallback,
      final String name
  ) {
    long parsed = longInteger(value, fallback, name);
    if (parsed > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Skill " + name + " is too large");
    }
    return (int) parsed;
  }

  private static long longInteger(
      final Object value,
      final long fallback,
      final String name
  ) {
    if (value == null) {
      return fallback;
    }
    if (!(value instanceof Number number)) {
      throw new IllegalArgumentException("Skill " + name + " must be an integer");
    }
    long parsed = number.longValue();
    if (Double.compare(number.doubleValue(), (double) parsed) != 0) {
      throw new IllegalArgumentException("Skill " + name + " must be an integer");
    }
    return parsed;
  }
}
