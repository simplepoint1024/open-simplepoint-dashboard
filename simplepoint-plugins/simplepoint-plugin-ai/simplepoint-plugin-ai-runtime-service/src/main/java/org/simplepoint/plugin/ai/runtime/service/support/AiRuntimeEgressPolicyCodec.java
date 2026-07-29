package org.simplepoint.plugin.ai.runtime.service.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Canonical JSON codec for default-deny workload DNS egress policy.
 */
@Component
public class AiRuntimeEgressPolicyCodec {

  private static final TypeReference<List<String>> STRING_LIST =
      new TypeReference<>() {
      };

  private static final Pattern HOST = Pattern.compile(
      "^(?:\\*\\.)?[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?$"
  );

  private static final int MAXIMUM_HOSTS = 32;

  private final ObjectMapper objectMapper;

  /**
   * Creates the deterministic egress policy codec.
   */
  public AiRuntimeEgressPolicyCodec(final ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * Validates mode coupling and produces deterministic policy JSON.
   */
  public String normalize(
      final String networkMode,
      final List<String> allowlist
  ) {
    if (!"egress".equals(networkMode)) {
      if (allowlist != null && !allowlist.isEmpty()) {
        throw new IllegalArgumentException(
            "Runtime egress hosts require egress network mode"
        );
      }
      return "[]";
    }
    return encode(normalizeHosts(allowlist));
  }

  /**
   * Decodes a persisted policy and repeats all trust-boundary validation.
   */
  public List<String> decode(
      final String networkMode,
      final String value
  ) {
    String json = value == null || value.isBlank() ? "[]" : value;
    try {
      List<String> decoded = objectMapper.readValue(json, STRING_LIST);
      if (!"egress".equals(networkMode)) {
        if (decoded != null && !decoded.isEmpty()) {
          throw new IllegalArgumentException(
              "Runtime egress hosts require egress network mode"
          );
        }
        return List.of();
      }
      return normalizeHosts(decoded);
    } catch (JsonProcessingException | IllegalArgumentException ex) {
      throw new IllegalStateException(
          "Runtime workload egress policy is invalid",
          ex
      );
    }
  }

  private List<String> normalizeHosts(final List<String> values) {
    if (values == null
        || values.isEmpty()
        || values.size() > MAXIMUM_HOSTS) {
      throw new IllegalArgumentException(
          "Runtime workload egress host count is invalid"
      );
    }
    LinkedHashSet<String> unique = new LinkedHashSet<>();
    for (String value : values) {
      String host = value == null ? "" : value.trim()
          .toLowerCase(Locale.ROOT);
      while (host.endsWith(".")) {
        host = host.substring(0, host.length() - 1);
      }
      if (!validHost(host) || !unique.add(host)) {
        throw new IllegalArgumentException(
            "Runtime workload egress hosts are invalid or duplicated"
        );
      }
    }
    return unique.stream().sorted().toList();
  }

  private boolean validHost(final String host) {
    if (host.isEmpty()
        || host.length() > 253
        || host.contains("..")
        || !HOST.matcher(host).matches()) {
      return false;
    }
    String candidate = host.startsWith("*.") ? host.substring(2) : host;
    if (candidate.chars().allMatch(character ->
        character == '.' || Character.isDigit(character))) {
      return false;
    }
    String[] labels = candidate.split("\\.", -1);
    if (labels.length < 2) {
      return false;
    }
    for (String label : labels) {
      if (label.isEmpty()
          || label.length() > 63
          || label.startsWith("-")
          || label.endsWith("-")) {
        return false;
      }
    }
    return true;
  }

  private String encode(final List<String> hosts) {
    try {
      return objectMapper.writeValueAsString(hosts);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(
          "Runtime workload egress policy cannot be encoded",
          ex
      );
    }
  }
}
