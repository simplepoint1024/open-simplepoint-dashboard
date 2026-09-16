package org.simplepoint.plugin.ai.runtime.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeMcpProfile;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeNode;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimePool;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProbeReport;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeStatus;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeSecretFile;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeWorkloadDispatchRequest;
import org.simplepoint.plugin.ai.runtime.api.properties.AiRuntimeProperties;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeNodeRepository;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeNodeOperations;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeSecretService;
import org.simplepoint.plugin.ai.runtime.service.support.AiRuntimeEgressPolicyCodec;
import org.simplepoint.plugin.ai.runtime.service.support.AiRuntimeProfileCodec;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/** Executes the protocol-level admission probe for a Runtime Profile. */
@Service
public class AiRuntimeMcpAdmissionService {

  private final AiRuntimeNodeRepository nodeRepository;

  private final AiRuntimeNodeOperations nodeOperations;

  private final AiRuntimeSecretService secretService;

  private final AiRuntimeProfileCodec profileCodec;

  private final AiRuntimeEgressPolicyCodec egressPolicyCodec;

  private final AiRuntimeProperties properties;

  private final ObjectMapper canonicalMapper;

  /** Creates the Runtime Node-backed MCP admission service. */
  public AiRuntimeMcpAdmissionService(
      final AiRuntimeNodeRepository nodeRepository,
      final AiRuntimeNodeOperations nodeOperations,
      final AiRuntimeSecretService secretService,
      final AiRuntimeProfileCodec profileCodec,
      final AiRuntimeEgressPolicyCodec egressPolicyCodec,
      final AiRuntimeProperties properties,
      final ObjectMapper objectMapper
  ) {
    this.nodeRepository = nodeRepository;
    this.nodeOperations = nodeOperations;
    this.secretService = secretService;
    this.profileCodec = profileCodec;
    this.egressPolicyCodec = egressPolicyCodec;
    this.properties = properties;
    this.canonicalMapper = objectMapper.copy()
        .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
  }

  /**
   * Starts an isolated disposable stdio server and returns a deterministic,
   * sanitized admission hash.
   */
  public AdmissionResult admit(
      final AiRuntimeMcpProfile profile,
      final RuntimeMcpProfileSpec spec,
      final String imageReference,
      final String imageDigest,
      final String imageAdmissionPolicyHash
  ) {
    if (spec.artifact().type() != RuntimeMcpProfileSpec.ArtifactType.OCI
        || spec.transport().type()
            == RuntimeMcpProfileSpec.TransportType.SSE_LEGACY) {
      throw new IllegalArgumentException(
          "Protocol admission supports OCI stdio or Streamable HTTP Profiles"
      );
    }
    AiRuntimeNode node = healthyNodes(spec).stream().findFirst()
        .orElseThrow(() -> new IllegalStateException(
            "No compatible healthy Tool Runtime Node is available for MCP admission"
        ));
    RuntimeMcpProbeReport report = nodeOperations.probe(
        node.getAdvertiseUrl(),
        dispatchRequest(profile, spec, imageReference, imageDigest)
    );
    if (report == null || !report.toolsSupported()
        || report.protocolVersion() == null
        || report.protocolVersion().isBlank()) {
      throw new IllegalStateException(
          "Tool Runtime Node returned an invalid MCP admission report"
      );
    }
    String evidence = canonicalJson(new AdmissionEvidence(
        imageAdmissionPolicyHash,
        report
    ));
    return new AdmissionResult(sha256(evidence), evidence);
  }

  /**
   * Probes a legacy image-only Pool before durable replicas are created.
   * New deployments should prefer immutable Runtime Profile revisions, while
   * this compatibility path still proves that the image actually speaks MCP.
   */
  public RuntimeMcpProbeReport admitLegacy(final AiRuntimePool pool) {
    if (pool == null) {
      throw new IllegalArgumentException(
          "Runtime pool is required for MCP admission"
      );
    }
    AiRuntimeNode node = healthyLegacyNodes(pool).stream().findFirst()
        .orElseThrow(() -> new IllegalArgumentException(
            "No compatible healthy Tool Runtime Node is available for MCP admission"
        ));
    RuntimeWorkloadDispatchRequest request = new RuntimeWorkloadDispatchRequest(
        "mcp-pool-admission",
        "mcp-pool-admission-lease",
        1,
        "mcp-pool-admission",
        pool.getTenantId() == null ? "" : pool.getTenantId(),
        withoutDigest(pool.getImageReference()) + "@" + pool.getImageDigest(),
        pool.getRequestedMemoryBytes(),
        pool.getRequestedNanoCpus(),
        pool.getRequestedPidsLimit(),
        30,
        pool.getNetworkMode(),
        egressPolicyCodec.decode(
            pool.getNetworkMode(), pool.getEgressAllowlistJson()
        ),
        secretService.resolve(
            pool.getSecretReferencesJson(),
            pool.getScopeType(),
            pool.getTenantId()
        ),
        "stdio",
        List.of(),
        List.of(),
        List.of(),
        null,
        java.util.Map.of(),
        "DEDICATED",
        1,
        List.of(),
        null,
        null,
        "STRICT",
        "RUNTIME_DEFAULT",
        List.of()
    );
    final RuntimeMcpProbeReport report;
    try {
      report = nodeOperations.probe(node.getAdvertiseUrl(), request);
    } catch (RuntimeException ex) {
      throw new IllegalArgumentException(
          "OCI image failed MCP admission: " + safeMessage(ex),
          ex
      );
    }
    if (report == null || !report.toolsSupported()
        || report.protocolVersion() == null
        || report.protocolVersion().isBlank()) {
      throw new IllegalArgumentException(
          "OCI image failed MCP admission: tools/list is not supported"
      );
    }
    return report;
  }

  private RuntimeWorkloadDispatchRequest dispatchRequest(
      final AiRuntimeMcpProfile profile,
      final RuntimeMcpProfileSpec spec,
      final String imageReference,
      final String imageDigest
  ) {
    return new RuntimeWorkloadDispatchRequest(
        "mcp-admission",
        "mcp-admission-lease",
        1,
        "mcp-admission",
        profile.getTenantId() == null ? "" : profile.getTenantId(),
        withoutDigest(imageReference) + "@" + imageDigest,
        properties.getDefaultWorkloadMemoryBytes(),
        properties.getDefaultWorkloadNanoCpus(),
        properties.getDefaultWorkloadPidsLimit(),
        30,
        networkMode(spec.network().mode()),
        httpHosts(spec.network()),
        admissionSecrets(profile, spec),
        transport(spec.transport().type()),
        spec.process().entrypoint(),
        spec.process().command(),
        spec.process().arguments(),
        spec.process().workingDirectory(),
        profileCodec.environment(spec),
        spec.session().mode().name(),
        spec.session().maxSessions(),
        profileCodec.storage(spec, profile.getScopeType(), profile.getTenantId()),
        spec.transport().containerPort(),
        spec.transport().path(),
        spec.sandbox().profile().name(),
        spec.process().userMode().name(),
        spec.process().initializationCommand()
    );
  }

  private List<RuntimeSecretFile> admissionSecrets(
      final AiRuntimeMcpProfile profile,
      final RuntimeMcpProfileSpec spec
  ) {
    List<RuntimeSecretFile> result = new ArrayList<>(
        secretService.resolveBindings(
            spec.secrets(), profile.getScopeType(), profile.getTenantId()
        )
    );
    for (int index = 0; index < spec.secrets().size(); index++) {
      RuntimeMcpProfileSpec.SecretBinding binding = spec.secrets().get(index);
      if (!binding.secretReference().startsWith("provider://")) {
        continue;
      }
      String targetPath = binding.target()
          == RuntimeMcpProfileSpec.SecretTarget.FILE
          ? binding.targetName()
          : "/run/secrets/simplepoint/.env/provider-binding-" + index;
      String targetEnvironment = binding.target()
          == RuntimeMcpProfileSpec.SecretTarget.ENV_AT_EXEC
          ? binding.targetName() : null;
      result.add(new RuntimeSecretFile(
          "provider-binding-" + index,
          "simplepoint-admission-placeholder",
          targetPath,
          targetEnvironment
      ));
    }
    return List.copyOf(result);
  }

  private List<AiRuntimeNode> healthyNodes(
      final RuntimeMcpProfileSpec spec
  ) {
    Instant now = Instant.now();
    return nodeRepository.findAllActive(PageRequest.of(0, 100)).stream()
        .filter(node -> node.getStatus() == RuntimeNodeStatus.READY)
        .filter(node -> node.getHeartbeatExpiresAt() != null
            && node.getHeartbeatExpiresAt().isAfter(now))
        .filter(node -> spec.network().mode()
            != RuntimeMcpProfileSpec.NetworkMode.HTTP_EGRESS
            || node.isAllowEgressNetwork())
        .sorted((left, right) -> left.getNodeId().compareTo(right.getNodeId()))
        .toList();
  }

  private List<AiRuntimeNode> healthyLegacyNodes(final AiRuntimePool pool) {
    Instant now = Instant.now();
    return nodeRepository.findAllActive(PageRequest.of(0, 100)).stream()
        .filter(node -> node.getStatus() == RuntimeNodeStatus.READY)
        .filter(node -> node.getHeartbeatExpiresAt() != null
            && node.getHeartbeatExpiresAt().isAfter(now))
        .filter(node -> pool.getRequestedMemoryBytes()
            <= node.getMaxWorkloadMemoryBytes())
        .filter(node -> pool.getRequestedNanoCpus()
            <= node.getMaxWorkloadNanoCpus())
        .filter(node -> pool.getRequestedPidsLimit()
            <= node.getMaxWorkloadPidsLimit())
        .filter(node -> !"bridge".equals(pool.getNetworkMode())
            || node.isAllowBridgeNetwork())
        .filter(node -> !"egress".equals(pool.getNetworkMode())
            || node.isAllowEgressNetwork())
        .sorted((left, right) -> left.getNodeId().compareTo(right.getNodeId()))
        .toList();
  }

  private static String networkMode(
      final RuntimeMcpProfileSpec.NetworkMode mode
  ) {
    return switch (mode) {
      case NONE -> "none";
      case HTTP_EGRESS -> "egress";
      case TCP_EGRESS -> "tcp-egress";
      case INTERNAL_SERVICE -> "internal-service";
    };
  }

  private static String transport(
      final RuntimeMcpProfileSpec.TransportType type
  ) {
    return switch (type) {
      case STDIO -> "stdio";
      case STREAMABLE_HTTP -> "streamable-http";
      case SSE_LEGACY -> throw new IllegalArgumentException(
          "Legacy SSE is not accepted for new Runtime Profiles"
      );
    };
  }

  private static List<String> httpHosts(
      final RuntimeMcpProfileSpec.NetworkPolicy policy
  ) {
    if (policy.mode() != RuntimeMcpProfileSpec.NetworkMode.HTTP_EGRESS) {
      return policy.allowlist();
    }
    return policy.allowlist().stream().map(value -> {
      String normalized = value == null ? "" : value.trim();
      if (normalized.endsWith(":443")) {
        return normalized.substring(0, normalized.length() - 4);
      }
      if (normalized.endsWith(":80")) {
        return normalized.substring(0, normalized.length() - 3);
      }
      if (normalized.contains(":")) {
        throw new IllegalArgumentException(
            "HTTP egress supports only port 80 or 443"
        );
      }
      return normalized;
    }).toList();
  }

  private static String withoutDigest(final String image) {
    int separator = image.lastIndexOf("@sha256:");
    return separator < 0 ? image : image.substring(0, separator);
  }

  private static String safeMessage(final RuntimeException error) {
    String message = error.getMessage();
    String value = message == null || message.isBlank()
        ? error.getClass().getSimpleName() : message.trim();
    return value.length() <= 768 ? value : value.substring(0, 768);
  }

  private String canonicalJson(final Object value) {
    try {
      return canonicalMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(
          "MCP admission report cannot be serialized",
          ex
      );
    }
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

  private record AdmissionEvidence(
      String imageAdmissionPolicyHash,
      RuntimeMcpProbeReport mcpProbe
  ) {
  }

  /** Immutable sanitized evidence persisted with a deployment revision. */
  public record AdmissionResult(String hash, String reportJson) {
  }
}
