package org.simplepoint.plugin.ai.mcp.service.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpTask;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayCancellationRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOperations;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayToolCallResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationManifest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationTaskCreateRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationTaskListRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationTaskRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationToolCallRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpTaskDescriptor;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpTaskListResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpTaskResult;
import org.simplepoint.plugin.ai.mcp.api.model.McpTaskStatus;
import org.simplepoint.plugin.ai.mcp.api.properties.AiMcpProperties;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpTaskRepository;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpPublicationRuntimeService;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpTaskService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authorization-bound MCP Tasks projection and fenced execution coordinator.
 */
@Service
public class AiMcpTaskServiceImpl implements AiMcpTaskService {

  private static final int MAXIMUM_PAGE_SIZE = 100;

  private final AiMcpTaskRepository repository;

  private final AiMcpPublicationRuntimeService publicationRuntimeService;

  private final McpGatewayOperations gatewayOperations;

  private final AiMcpProperties properties;

  private final ObjectMapper objectMapper;

  /**
   * Creates the durable Task compatibility service.
   */
  public AiMcpTaskServiceImpl(
      final AiMcpTaskRepository repository,
      final AiMcpPublicationRuntimeService publicationRuntimeService,
      final McpGatewayOperations gatewayOperations,
      final AiMcpProperties properties,
      final ObjectMapper objectMapper
  ) {
    this.repository = repository;
    this.publicationRuntimeService = publicationRuntimeService;
    this.gatewayOperations = gatewayOperations;
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public McpTaskDescriptor create(
      final McpPublicationTaskCreateRequest request
  ) {
    if (request == null) {
      throw new IllegalArgumentException(
          "MCP Task create request must not be null"
      );
    }
    String publicationCode = required(
        request.publicationCode(),
        "MCP publication code",
        128
    );
    String toolName = required(request.toolName(), "MCP Tool name", 256);
    final String subject = required(
        request.subject(),
        "MCP subject",
        512
    );
    final String clientId = required(
        request.clientId(),
        "MCP client ID",
        512
    );
    McpPublicationManifest manifest =
        publicationRuntimeService.manifest(publicationCode);
    if (manifest.tools().stream().noneMatch(tool ->
        toolName.equals(tool.name()))) {
      throw new IllegalArgumentException(
          "MCP Tool is not part of the published snapshot: " + toolName
      );
    }
    Map<String, Object> arguments = request.arguments() == null
        ? Map.of() : new LinkedHashMap<>(request.arguments());
    String argumentsJson = write(arguments);
    assertPayloadSize(argumentsJson, "MCP Task arguments");
    long ttlMillis = boundedTtl(request.ttl());
    Instant now = Instant.now();
    AiMcpTask task = new AiMcpTask();
    task.setPublicationCode(publicationCode);
    task.setToolName(toolName);
    task.setSubject(subject);
    task.setSubjectHash(sha256(subject));
    task.setClientId(clientId);
    task.setClientHash(sha256(clientId));
    task.setSourceSessionId(optional(request.sessionId(), 512));
    task.setOperationId(UUID.randomUUID().toString());
    task.setArgumentsJson(argumentsJson);
    task.setStatus(McpTaskStatus.PENDING);
    task.setStatusMessage("The operation is queued for execution.");
    task.setTtlMillis(ttlMillis);
    task.setPollIntervalMillis(pollInterval().toMillis());
    task.setExpiresAt(now.plusMillis(ttlMillis));
    task.setNextAttemptAt(now);
    task.setLeaseToken(0L);
    task.setAttemptCount(0);
    return descriptor(repository.save(task));
  }

  @Override
  @Transactional(readOnly = true)
  public McpTaskDescriptor find(final McpPublicationTaskRequest request) {
    return descriptor(requireVisible(request));
  }

  @Override
  @Transactional(readOnly = true)
  public McpTaskListResult findAll(
      final McpPublicationTaskListRequest request
  ) {
    if (request == null) {
      throw new IllegalArgumentException(
          "MCP Task list request must not be null"
      );
    }
    String publicationCode = required(
        request.publicationCode(),
        "MCP publication code",
        128
    );
    String subjectHash = sha256(required(
        request.subject(),
        "MCP subject",
        512
    ));
    String clientHash = sha256(required(
        request.clientId(),
        "MCP client ID",
        512
    ));
    Cursor cursor = decodeCursor(request.cursor());
    int limit = Math.max(
        1,
        Math.min(
            request.limit() <= 0 ? 50 : request.limit(),
            MAXIMUM_PAGE_SIZE
        )
    );
    Instant now = Instant.now();
    PageRequest pageable = PageRequest.of(0, limit + 1);
    List<AiMcpTask> page = cursor == null
        ? repository.findVisibleFirstPage(
            publicationCode,
            subjectHash,
            clientHash,
            now,
            pageable
        )
        : repository.findVisible(
            publicationCode,
            subjectHash,
            clientHash,
            now,
            cursor.createdAt(),
            cursor.id(),
            pageable
        );
    boolean hasMore = page.size() > limit;
    List<AiMcpTask> visible = page.stream().limit(limit).toList();
    String nextCursor = hasMore
        ? encodeCursor(visible.get(visible.size() - 1)) : null;
    return new McpTaskListResult(
        visible.stream().map(this::descriptor).toList(),
        nextCursor
    );
  }

  @Override
  @Transactional(readOnly = true)
  public McpTaskResult result(final McpPublicationTaskRequest request) {
    AiMcpTask task = requireVisible(request);
    McpGatewayToolCallResult result = task.getResultJson() == null
        ? null : readResult(task.getResultJson());
    return new McpTaskResult(
        descriptor(task),
        result,
        task.getErrorCode(),
        task.getErrorMessage()
    );
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public McpTaskDescriptor cancel(final McpPublicationTaskRequest request) {
    AiMcpTask visible = requireVisible(request);
    AiMcpTask task = repository.findActiveByIdForUpdate(visible.getId())
        .orElseThrow(() -> new IllegalArgumentException(
            "MCP Task does not exist"
        ));
    if (terminal(task.getStatus())) {
      throw new IllegalArgumentException(
          "MCP Task is already in a terminal status"
      );
    }
    task.setStatus(McpTaskStatus.CANCELLED);
    task.setStatusMessage("The task was cancelled by request.");
    task.setCompletedAt(Instant.now());
    clearLease(task);
    repository.save(task);
    try {
      gatewayOperations.cancel(new McpGatewayCancellationRequest(
          task.getOperationId(),
          "MCP Task cancelled by requestor"
      ));
    } catch (RuntimeException ignored) {
      // The durable CANCELLED state wins even if an uncertain remote call can
      // no longer be interrupted.
    }
    return descriptor(task);
  }

  /**
   * Claims due or abandoned tasks using a short fenced lease.
   */
  @Transactional(rollbackFor = Exception.class)
  public List<ExecutionToken> claim(
      final String workerId,
      final int capacity
  ) {
    if (capacity <= 0) {
      return List.of();
    }
    Instant now = Instant.now();
    List<AiMcpTask> candidates = repository.findClaimableForUpdate(
        now,
        PageRequest.of(0, Math.min(capacity, batchSize()))
    );
    return candidates.stream().map(task -> {
      if (task.getAttemptCount() >= maximumAttempts()) {
        task.setStatus(McpTaskStatus.FAILED);
        task.setStatusMessage("The task retry limit was exhausted.");
        task.setErrorCode("MCP_TASK_RETRY_EXHAUSTED");
        task.setErrorMessage("MCP Task retry limit was exhausted");
        task.setCompletedAt(now);
        clearLease(task);
        repository.save(task);
        return null;
      }
      task.setStatus(McpTaskStatus.RUNNING);
      task.setStatusMessage("The operation is now in progress.");
      task.setStartedAt(task.getStartedAt() == null
          ? now : task.getStartedAt());
      task.setAttemptCount(task.getAttemptCount() + 1);
      task.setLeaseOwner(workerId);
      task.setLeaseToken(task.getLeaseToken() + 1);
      task.setLeaseExpiresAt(now.plus(leaseDuration()));
      task.setNextAttemptAt(null);
      repository.save(task);
      return new ExecutionToken(
          task.getId(),
          workerId,
          task.getLeaseToken()
      );
    }).filter(java.util.Objects::nonNull).toList();
  }

  /**
   * Loads immutable execution input after verifying the current lease.
   */
  @Transactional(readOnly = true)
  public McpPublicationToolCallRequest executionRequest(
      final ExecutionToken token
  ) {
    AiMcpTask task = requireOwned(token);
    return new McpPublicationToolCallRequest(
        task.getPublicationCode(),
        task.getToolName(),
        readArguments(task.getArgumentsJson()),
        task.getSubject(),
        task.getClientId(),
        task.getSourceSessionId(),
        task.getOperationId()
    );
  }

  /**
   * Commits the exact underlying Tool result if the lease remains current.
   */
  @Transactional(rollbackFor = Exception.class)
  public void complete(
      final ExecutionToken token,
      final McpGatewayToolCallResult result
  ) {
    AiMcpTask task = requireOwnedForUpdate(token);
    String resultJson = write(result);
    assertPayloadSize(resultJson, "MCP Task result");
    task.setResultJson(resultJson);
    task.setStatus(McpTaskStatus.COMPLETED);
    task.setStatusMessage("The operation completed successfully.");
    task.setCompletedAt(Instant.now());
    clearLease(task);
    repository.save(task);
  }

  /**
   * Commits a terminal execution error if the lease remains current.
   */
  @Transactional(rollbackFor = Exception.class)
  public void fail(
      final ExecutionToken token,
      final RuntimeException failure
  ) {
    AiMcpTask task = requireOwnedForUpdate(token);
    task.setStatus(McpTaskStatus.FAILED);
    task.setStatusMessage("The operation failed.");
    task.setErrorCode("MCP_TASK_EXECUTION_FAILED");
    task.setErrorMessage(truncate(
        failure == null ? null : failure.getMessage(),
        1024,
        "MCP Task execution failed"
    ));
    task.setCompletedAt(Instant.now());
    clearLease(task);
    repository.save(task);
  }

  /**
   * Renews an active lease only for the exact fencing generation.
   */
  @Transactional(rollbackFor = Exception.class)
  public boolean renew(final ExecutionToken token) {
    AiMcpTask task = repository.findActiveByIdForUpdate(token.taskId())
        .orElse(null);
    Instant now = Instant.now();
    if (task == null
        || task.getStatus() != McpTaskStatus.RUNNING
        || !token.workerId().equals(task.getLeaseOwner())
        || token.leaseToken() != task.getLeaseToken()
        || task.getLeaseExpiresAt() == null
        || !now.isBefore(task.getLeaseExpiresAt())) {
      return false;
    }
    task.setLeaseExpiresAt(now.plus(leaseDuration()));
    repository.save(task);
    return true;
  }

  private AiMcpTask requireVisible(final McpPublicationTaskRequest request) {
    if (request == null) {
      throw new IllegalArgumentException(
          "MCP Task request must not be null"
      );
    }
    String id = required(request.taskId(), "MCP Task ID", 64);
    AiMcpTask task = repository.findActiveById(id)
        .filter(candidate -> candidate.getExpiresAt().isAfter(Instant.now()))
        .orElseThrow(() -> new IllegalArgumentException(
            "MCP Task does not exist"
        ));
    if (!required(
        request.publicationCode(),
        "MCP publication code",
        128
    ).equals(task.getPublicationCode())
        || !sha256(required(request.subject(), "MCP subject", 512))
            .equals(task.getSubjectHash())
        || !sha256(required(request.clientId(), "MCP client ID", 512))
            .equals(task.getClientHash())) {
      throw new IllegalArgumentException("MCP Task does not exist");
    }
    return task;
  }

  private AiMcpTask requireOwned(final ExecutionToken token) {
    AiMcpTask task = repository.findActiveById(token.taskId())
        .orElseThrow(() -> new StaleMcpTaskLeaseException(
            "MCP Task no longer exists"
        ));
    assertOwned(task, token);
    return task;
  }

  private AiMcpTask requireOwnedForUpdate(final ExecutionToken token) {
    AiMcpTask task = repository.findActiveByIdForUpdate(token.taskId())
        .orElseThrow(() -> new StaleMcpTaskLeaseException(
            "MCP Task no longer exists"
        ));
    assertOwned(task, token);
    return task;
  }

  private static void assertOwned(
      final AiMcpTask task,
      final ExecutionToken token
  ) {
    Instant now = Instant.now();
    if (task.getStatus() != McpTaskStatus.RUNNING
        || !token.workerId().equals(task.getLeaseOwner())
        || token.leaseToken() != task.getLeaseToken()
        || task.getLeaseExpiresAt() == null
        || !now.isBefore(task.getLeaseExpiresAt())) {
      throw new StaleMcpTaskLeaseException("MCP Task lease is stale");
    }
  }

  private McpTaskDescriptor descriptor(final AiMcpTask task) {
    return new McpTaskDescriptor(
        task.getId(),
        protocolStatus(task.getStatus()),
        task.getStatusMessage(),
        timestamp(task.getCreatedAt()),
        timestamp(task.getUpdatedAt()),
        task.getTtlMillis(),
        task.getPollIntervalMillis()
    );
  }

  private static String protocolStatus(final McpTaskStatus status) {
    return switch (status) {
      case PENDING, RUNNING -> "working";
      case COMPLETED -> "completed";
      case FAILED -> "failed";
      case CANCELLED -> "cancelled";
    };
  }

  private long boundedTtl(final long requested) {
    long defaultMillis = positive(
        properties.getTaskDefaultTtl(),
        Duration.ofHours(1)
    ).toMillis();
    long maximumMillis = positive(
        properties.getTaskMaximumTtl(),
        Duration.ofHours(24)
    ).toMillis();
    long candidate = requested <= 0 ? defaultMillis : requested;
    return Math.max(1_000L, Math.min(candidate, maximumMillis));
  }

  private Duration pollInterval() {
    return positive(
        properties.getTaskPollInterval(),
        Duration.ofSeconds(1)
    );
  }

  private Duration leaseDuration() {
    return positive(
        properties.getTaskLeaseDuration(),
        Duration.ofMinutes(2)
    );
  }

  private int batchSize() {
    return Math.max(
        1,
        Math.min(properties.getTaskWorkerBatchSize(), 64)
    );
  }

  private int maximumAttempts() {
    return Math.max(1, properties.getTaskMaximumAttempts());
  }

  private void assertPayloadSize(
      final String json,
      final String label
  ) {
    int maximum = Math.max(
        1024,
        properties.getTaskMaximumPayloadBytes()
    );
    if (json.getBytes(StandardCharsets.UTF_8).length > maximum) {
      throw new IllegalArgumentException(
          label + " exceeds the configured maximum size"
      );
    }
  }

  private String write(final Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException(
          "Unable to serialize MCP Task payload",
          ex
      );
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readArguments(final String json) {
    try {
      return objectMapper.readValue(json, LinkedHashMap.class);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(
          "Stored MCP Task arguments are invalid",
          ex
      );
    }
  }

  private McpGatewayToolCallResult readResult(final String json) {
    try {
      return objectMapper.readValue(
          json,
          McpGatewayToolCallResult.class
      );
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(
          "Stored MCP Task result is invalid",
          ex
      );
    }
  }

  private static String encodeCursor(final AiMcpTask task) {
    String value = task.getCreatedAt() + "|" + task.getId();
    return Base64.getUrlEncoder().withoutPadding().encodeToString(
        value.getBytes(StandardCharsets.UTF_8)
    );
  }

  private static Cursor decodeCursor(final String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      String decoded = new String(
          Base64.getUrlDecoder().decode(value),
          StandardCharsets.UTF_8
      );
      int separator = decoded.indexOf('|');
      if (separator <= 0 || separator == decoded.length() - 1) {
        throw new IllegalArgumentException("invalid cursor");
      }
      return new Cursor(
          Instant.parse(decoded.substring(0, separator)),
          decoded.substring(separator + 1)
      );
    } catch (RuntimeException ex) {
      throw new IllegalArgumentException("MCP Task cursor is invalid", ex);
    }
  }

  private static String required(
      final String value,
      final String label,
      final int maximumLength
  ) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    String normalized = value.trim();
    if (normalized.length() > maximumLength) {
      throw new IllegalArgumentException(label + " is too long");
    }
    return normalized;
  }

  private static String optional(
      final String value,
      final int maximumLength
  ) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.trim();
    if (normalized.length() > maximumLength) {
      throw new IllegalArgumentException("MCP value is too long");
    }
    return normalized;
  }

  private static String sha256(final String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(
          value.getBytes(StandardCharsets.UTF_8)
      );
      return java.util.HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable", ex);
    }
  }

  private static String timestamp(final Instant value) {
    return value == null ? Instant.now().toString() : value.toString();
  }

  private static boolean terminal(final McpTaskStatus status) {
    return status == McpTaskStatus.COMPLETED
        || status == McpTaskStatus.FAILED
        || status == McpTaskStatus.CANCELLED;
  }

  private static Duration positive(
      final Duration value,
      final Duration fallback
  ) {
    return value == null || value.isZero() || value.isNegative()
        ? fallback : value;
  }

  private static String truncate(
      final String value,
      final int maximum,
      final String fallback
  ) {
    String normalized = value == null || value.isBlank()
        ? fallback : value.trim();
    return normalized.length() <= maximum
        ? normalized : normalized.substring(0, maximum);
  }

  private static void clearLease(final AiMcpTask task) {
    task.setLeaseOwner(null);
    task.setLeaseExpiresAt(null);
    task.setNextAttemptAt(null);
  }

  private record Cursor(Instant createdAt, String id) {
  }

  /**
   * Immutable lease generation required for all Task commits.
   */
  public record ExecutionToken(
      String taskId,
      String workerId,
      long leaseToken
  ) {
  }

  /**
   * Signals that another worker generation or cancellation won the race.
   */
  public static class StaleMcpTaskLeaseException
      extends IllegalStateException {

    /**
     * Creates a stale Task lease failure.
     */
    public StaleMcpTaskLeaseException(final String message) {
      super(message);
    }
  }
}
