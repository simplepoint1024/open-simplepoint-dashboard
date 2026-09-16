package org.simplepoint.plugin.ai.runtime.service.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeStorageMount;
import org.springframework.stereotype.Component;

/** Reads immutable Runtime Profile snapshots at Pool and dispatch boundaries. */
@Component
public class AiRuntimeProfileCodec {

  private final ObjectMapper objectMapper;

  /** Creates the Profile snapshot codec. */
  public AiRuntimeProfileCodec(final ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** Decodes a required canonical Runtime Profile snapshot. */
  public RuntimeMcpProfileSpec decode(final String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Runtime Profile snapshot is required");
    }
    try {
      return objectMapper.readValue(value, RuntimeMcpProfileSpec.class);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(
          "Runtime Profile snapshot is invalid",
          ex
      );
    }
  }

  /** Builds the non-sensitive child process environment. */
  public Map<String, String> environment(final RuntimeMcpProfileSpec spec) {
    Map<String, String> result = new LinkedHashMap<>();
    for (RuntimeMcpProfileSpec.ConfigurationBinding binding
        : spec.configuration()) {
      if (result.putIfAbsent(
          binding.targetEnvironment(), binding.value()
      ) != null) {
        throw new IllegalStateException(
            "Runtime Profile contains duplicate environment targets"
        );
      }
    }
    return Map.copyOf(result);
  }

  /** Resolves opaque storage references to platform-owned Docker volumes. */
  public List<RuntimeStorageMount> storage(
      final RuntimeMcpProfileSpec spec,
      final AiResourceScope scopeType,
      final String tenantId
  ) {
    return spec.storage().stream().map(binding -> new RuntimeStorageMount(
        binding.type().name(),
        ephemeral(binding.type()) ? null : managedVolume(
            scopeType, tenantId, binding.storageReference()
        ),
        binding.targetPath(),
        binding.readOnly(),
        binding.sizeBytes()
    )).toList();
  }

  private static boolean ephemeral(
      final RuntimeMcpProfileSpec.StorageType type
  ) {
    return type == RuntimeMcpProfileSpec.StorageType.TMPFS
        || type == RuntimeMcpProfileSpec.StorageType.EPHEMERAL;
  }

  private static String managedVolume(
      final AiResourceScope scopeType,
      final String tenantId,
      final String reference
  ) {
    if (scopeType == null || reference == null || reference.isBlank()) {
      throw new IllegalArgumentException(
          "Managed Runtime storage identity is invalid"
      );
    }
    String material = scopeType.name() + '\0'
        + (tenantId == null ? "" : tenantId) + '\0' + reference;
    return "open-simplepoint-managed-" + sha256(material).substring(0, 40);
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
}
