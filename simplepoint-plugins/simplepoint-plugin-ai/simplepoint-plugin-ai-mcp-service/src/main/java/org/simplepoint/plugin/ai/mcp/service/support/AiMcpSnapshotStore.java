package org.simplepoint.plugin.ai.mcp.service.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpCapabilitySnapshot;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpServerDefinition;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayDiscoveryResult;
import org.simplepoint.plugin.ai.mcp.api.model.McpServerStatus;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpCapabilitySnapshotRepository;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpServerDefinitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists immutable capability snapshots after network discovery has completed.
 */
@Service
public class AiMcpSnapshotStore {

  private final AiMcpServerDefinitionRepository serverRepository;

  private final AiMcpCapabilitySnapshotRepository snapshotRepository;

  private final ObjectMapper canonicalMapper;

  /**
   * Creates the snapshot store.
   *
   * @param serverRepository server repository
   * @param snapshotRepository snapshot repository
   * @param objectMapper JSON mapper
   */
  public AiMcpSnapshotStore(
      final AiMcpServerDefinitionRepository serverRepository,
      final AiMcpCapabilitySnapshotRepository snapshotRepository,
      final ObjectMapper objectMapper
  ) {
    this.serverRepository = serverRepository;
    this.snapshotRepository = snapshotRepository;
    this.canonicalMapper = objectMapper.copy()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
  }

  /**
   * Saves a successful discovery result and activates the new snapshot atomically.
   *
   * @param serverId server id
   * @param discovery discovery result
   * @return persisted snapshot
   */
  @Transactional(rollbackFor = Exception.class)
  public AiMcpCapabilitySnapshot saveDiscovery(
      final String serverId,
      final McpGatewayDiscoveryResult discovery
  ) {
    AiMcpServerDefinition server = serverRepository.findActiveById(serverId)
        .orElseThrow(() -> new IllegalArgumentException("MCP server does not exist"));
    String capabilitiesJson = writeJson(discovery.capabilities());
    String toolsJson = writeJson(discovery.tools());
    String resourcesJson = writeJson(discovery.resources());
    String resourceTemplatesJson = writeJson(discovery.resourceTemplates());
    String promptsJson = writeJson(discovery.prompts());
    AiMcpCapabilitySnapshot snapshot = new AiMcpCapabilitySnapshot();
    snapshot.setServerId(server.getId());
    snapshot.setScopeType(server.getScopeType());
    snapshot.setTenantId(server.getTenantId());
    snapshot.setProtocolVersion(discovery.protocolVersion());
    snapshot.setRemoteServerName(required(discovery.serverName(), "unknown"));
    snapshot.setRemoteServerVersion(required(discovery.serverVersion(), "unknown"));
    snapshot.setCapabilitiesJson(capabilitiesJson);
    snapshot.setToolsJson(toolsJson);
    snapshot.setResourcesJson(resourcesJson);
    snapshot.setResourceTemplatesJson(resourceTemplatesJson);
    snapshot.setPromptsJson(promptsJson);
    snapshot.setSchemaHash(sha256(
        capabilitiesJson + "\n" + toolsJson + "\n" + resourcesJson
            + "\n" + resourceTemplatesJson + "\n" + promptsJson
    ));
    snapshot.setDiscoveredAt(Instant.now());
    AiMcpCapabilitySnapshot saved = snapshotRepository.save(snapshot);

    server.setProtocolVersion(discovery.protocolVersion());
    server.setRemoteServerName(discovery.serverName());
    server.setRemoteServerVersion(discovery.serverVersion());
    server.setActiveSnapshotId(saved.getId());
    server.setLastDiscoveredAt(saved.getDiscoveredAt());
    server.setLastError(null);
    server.setStatus(Boolean.TRUE.equals(server.getEnabled())
        ? McpServerStatus.READY : McpServerStatus.DISABLED);
    serverRepository.save(server);
    return saved;
  }

  /**
   * Records a sanitized discovery failure without deleting the previous snapshot.
   *
   * @param serverId server id
   * @param message failure message
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
  public void saveFailure(final String serverId, final String message) {
    serverRepository.findActiveById(serverId).ifPresent(server -> {
      server.setStatus(McpServerStatus.ERROR);
      server.setLastError(truncate(message));
      serverRepository.save(server);
    });
  }

  private String writeJson(final Object value) {
    try {
      return canonicalMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Unable to serialize MCP capability snapshot", ex);
    }
  }

  private static String sha256(final String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }

  private static String required(final String value, final String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private static String truncate(final String value) {
    if (value == null || value.isBlank()) {
      return "Remote MCP operation failed";
    }
    String normalized = value.replaceAll("\\s+", " ").trim();
    return normalized.length() <= 1024 ? normalized : normalized.substring(0, 1024);
  }
}
