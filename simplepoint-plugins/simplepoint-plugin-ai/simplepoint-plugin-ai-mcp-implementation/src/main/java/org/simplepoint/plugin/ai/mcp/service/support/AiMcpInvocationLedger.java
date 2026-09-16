package org.simplepoint.plugin.ai.mcp.service.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpInvocation;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpServerDefinition;
import org.simplepoint.plugin.ai.mcp.api.model.McpCapabilityType;
import org.simplepoint.plugin.ai.mcp.api.model.McpInvocationStatus;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpInvocationRepository;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimePoolRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes a metadata-only ledger without storing capability requests or results.
 */
@Service
public class AiMcpInvocationLedger {

  private final AiMcpInvocationRepository repository;

  private final ObjectMapper canonicalMapper;

  private final AiRuntimePoolRepository runtimePoolRepository;

  /**
   * Creates the ledger.
   *
   * @param repository invocation repository
   * @param objectMapper JSON mapper
   * @param runtimePoolRepository Runtime Pool repository
   */
  public AiMcpInvocationLedger(
      final AiMcpInvocationRepository repository,
      final ObjectMapper objectMapper,
      final AiRuntimePoolRepository runtimePoolRepository
  ) {
    this.repository = repository;
    this.runtimePoolRepository = runtimePoolRepository;
    this.canonicalMapper = objectMapper.copy()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
  }

  /**
   * Starts a durable invocation record.
   *
   * @param server server definition
   * @param snapshotId active capability snapshot
   * @param capabilityType capability type
   * @param capabilityName capability name or URI
   * @param request request payload
   * @return invocation id
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
  public String start(
      final AiMcpServerDefinition server,
      final String snapshotId,
      final McpCapabilityType capabilityType,
      final String capabilityName,
      final Object request
  ) {
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    return start(
        server,
        snapshotId,
        capabilityType,
        capabilityName,
        request,
        context == null ? null : context.getUserId(),
        null,
        context == null ? null : context.getContextId()
    );
  }

  /**
   * Starts a publication invocation with the authenticated external subject.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
  public String start(
      final AiMcpServerDefinition server,
      final String snapshotId,
      final McpCapabilityType capabilityType,
      final String capabilityName,
      final Object request,
      final String subject,
      final String clientId,
      final String sessionId
  ) {
    AiMcpInvocation invocation = new AiMcpInvocation();
    invocation.setScopeType(server.getScopeType());
    invocation.setTenantId(server.getTenantId());
    invocation.setUserId(truncateIdentifier(subject, 64));
    invocation.setClientId(truncateIdentifier(clientId, 2048));
    invocation.setContextId(truncateIdentifier(sessionId, 64));
    invocation.setTraceId(UUID.randomUUID().toString().replace("-", ""));
    invocation.setServerId(server.getId());
    invocation.setSnapshotId(snapshotId);
    runtimePoolRepository.findActiveByServerAndScope(
        server.getId(), server.getScopeType(), server.getTenantId()
    ).ifPresent(pool -> invocation.setRuntimeRevisionId(
        pool.getActiveRevisionId()
    ));
    invocation.setCapabilityType(capabilityType);
    invocation.setCapabilityName(capabilityName);
    invocation.setRequestHash(hash(request));
    invocation.setStatus(McpInvocationStatus.RUNNING);
    invocation.setStartedAt(Instant.now());
    return repository.save(invocation).getId();
  }

  /**
   * Marks an invocation successful.
   *
   * @param invocationId invocation id
   * @param result result object
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
  public void succeed(final String invocationId, final Object result) {
    finish(invocationId, McpInvocationStatus.SUCCEEDED, hash(result), null);
  }

  /**
   * Marks an invocation failed.
   *
   * @param invocationId invocation id
   * @param exception failure
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
  public void fail(final String invocationId, final RuntimeException exception) {
    finish(
        invocationId,
        McpInvocationStatus.FAILED,
        null,
        "Remote MCP operation failed"
    );
  }

  private void finish(
      final String invocationId,
      final McpInvocationStatus status,
      final String resultHash,
      final String error
  ) {
    repository.findById(invocationId).ifPresent(invocation -> {
      Instant completedAt = Instant.now();
      invocation.setStatus(status);
      invocation.setCompletedAt(completedAt);
      invocation.setDurationMillis(Duration.between(
          invocation.getStartedAt(),
          completedAt
      ).toMillis());
      invocation.setResultHash(resultHash);
      if (error != null) {
        invocation.setErrorCode("MCP_CAPABILITY_CALL_FAILED");
        invocation.setErrorMessage(error);
      }
      repository.save(invocation);
    });
  }

  private String hash(final Object value) {
    try {
      byte[] bytes = canonicalMapper.writeValueAsBytes(value);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("MCP payload is not valid JSON", ex);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }

  private static String truncateIdentifier(final String value, final int length) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.trim();
    return normalized.length() <= length ? normalized : normalized.substring(0, length);
  }
}
