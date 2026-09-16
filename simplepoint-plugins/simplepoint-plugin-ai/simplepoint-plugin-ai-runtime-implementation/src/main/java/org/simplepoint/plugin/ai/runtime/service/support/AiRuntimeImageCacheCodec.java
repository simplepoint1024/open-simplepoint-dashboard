package org.simplepoint.plugin.ai.runtime.service.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Canonical bounded codec for runtime-node content-addressed image caches.
 */
@Component
public class AiRuntimeImageCacheCodec {

  private static final TypeReference<List<String>> STRING_LIST =
      new TypeReference<>() {
      };

  private static final Pattern DIGEST =
      Pattern.compile("^sha256:[a-f0-9]{64}$");

  private static final int MAXIMUM_DIGESTS = 512;

  private final ObjectMapper objectMapper;

  /**
   * Creates the deterministic cache snapshot codec.
   */
  public AiRuntimeImageCacheCodec(final ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * Validates, sorts, and encodes one cache snapshot.
   */
  public String encode(final Collection<String> values) {
    try {
      return objectMapper.writeValueAsString(normalize(values));
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(
          "Runtime image cache cannot be encoded",
          ex
      );
    }
  }

  /**
   * Decodes persisted data and repeats trust-boundary validation.
   */
  public List<String> decode(final String value) {
    try {
      return normalize(objectMapper.readValue(
          value == null || value.isBlank() ? "[]" : value,
          STRING_LIST
      ));
    } catch (JsonProcessingException | IllegalArgumentException ex) {
      throw new IllegalStateException(
          "Runtime image cache is invalid",
          ex
      );
    }
  }

  /**
   * Returns whether a node already owns the requested content digest.
   */
  public boolean contains(final String value, final String digest) {
    return decode(value).contains(normalizeDigest(digest));
  }

  /**
   * Adds a freshly prepared digest to a persisted cache snapshot.
   */
  public String add(final String value, final String digest) {
    LinkedHashSet<String> result = new LinkedHashSet<>(decode(value));
    result.add(normalizeDigest(digest));
    return encode(result);
  }

  private List<String> normalize(final Collection<String> values) {
    if (values == null || values.isEmpty()) {
      return List.of();
    }
    if (values.size() > MAXIMUM_DIGESTS) {
      throw new IllegalArgumentException(
          "Runtime image cache exceeds the configured limit"
      );
    }
    LinkedHashSet<String> unique = new LinkedHashSet<>();
    for (String value : values) {
      if (!unique.add(normalizeDigest(value))) {
        continue;
      }
    }
    return unique.stream().sorted().toList();
  }

  private String normalizeDigest(final String value) {
    String normalized = value == null ? "" : value.trim().toLowerCase();
    if (!DIGEST.matcher(normalized).matches()) {
      throw new IllegalArgumentException(
          "Runtime image digest is invalid"
      );
    }
    return normalized;
  }
}
