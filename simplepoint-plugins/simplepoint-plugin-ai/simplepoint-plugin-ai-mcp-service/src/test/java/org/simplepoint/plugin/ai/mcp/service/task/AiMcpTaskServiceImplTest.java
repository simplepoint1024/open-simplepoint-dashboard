package org.simplepoint.plugin.ai.mcp.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpTask;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayCancellationRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOperations;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayToolCallResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationManifest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationTaskCreateRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationTaskRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpToolDescriptor;
import org.simplepoint.plugin.ai.mcp.api.model.McpTaskStatus;
import org.simplepoint.plugin.ai.mcp.api.properties.AiMcpProperties;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpTaskRepository;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpPublicationRuntimeService;
import org.simplepoint.plugin.ai.mcp.service.task.AiMcpTaskServiceImpl.ExecutionToken;
import org.springframework.data.domain.Pageable;

class AiMcpTaskServiceImplTest {

  private final AiMcpTaskRepository repository =
      mock(AiMcpTaskRepository.class);

  private final AiMcpPublicationRuntimeService publicationRuntime =
      mock(AiMcpPublicationRuntimeService.class);

  private final McpGatewayOperations gatewayOperations =
      mock(McpGatewayOperations.class);

  private final AiMcpProperties properties = new AiMcpProperties();

  private final AiMcpTaskServiceImpl service = new AiMcpTaskServiceImpl(
      repository,
      publicationRuntime,
      gatewayOperations,
      properties,
      new ObjectMapper()
  );

  @BeforeEach
  void configureRepositorySave() {
    when(repository.save(any(AiMcpTask.class))).thenAnswer(invocation -> {
      AiMcpTask task = invocation.getArgument(0);
      if (task.getId() == null) {
        task.setId("task-1");
      }
      task.prePersist();
      return task;
    });
  }

  @Test
  void createsWorkingTaskBoundToAuthorizationContext() {
    when(publicationRuntime.manifest("demo")).thenReturn(manifest());

    var created = service.create(new McpPublicationTaskCreateRequest(
        "demo",
        "echo",
        Map.of("message", "hello"),
        "subject-1",
        "client-1",
        "session-1",
        60_000
    ));

    assertThat(created.taskId()).isEqualTo("task-1");
    assertThat(created.status()).isEqualTo("working");
    assertThat(created.ttl()).isEqualTo(60_000);
    verify(repository).save(any(AiMcpTask.class));
  }

  @Test
  void fencesCompletionAndPersistsExactToolResult() {
    AiMcpTask task = task();
    when(repository.findClaimableForUpdate(
        any(Instant.class),
        any(Pageable.class)
    )).thenReturn(List.of(task));
    List<ExecutionToken> claimed = service.claim("worker-1", 1);
    assertThat(claimed).hasSize(1);
    when(repository.findActiveByIdForUpdate("task-1"))
        .thenReturn(Optional.of(task));
    McpGatewayToolCallResult result = new McpGatewayToolCallResult(
        List.of(Map.of("type", "text", "text", "done")),
        false,
        Map.of("value", "done"),
        Map.of()
    );

    service.complete(claimed.get(0), result);

    assertThat(task.getStatus()).isEqualTo(McpTaskStatus.COMPLETED);
    assertThat(task.getResultJson()).contains("\"done\"");
    assertThat(task.getLeaseOwner()).isNull();
  }

  @Test
  void cancellationIsTerminalAndInterruptsClusterOperation() {
    AiMcpTask task = task();
    when(repository.findActiveById("task-1"))
        .thenReturn(Optional.of(task));
    when(repository.findActiveByIdForUpdate("task-1"))
        .thenReturn(Optional.of(task));

    var cancelled = service.cancel(request("subject-1", "client-1"));

    assertThat(cancelled.status()).isEqualTo("cancelled");
    assertThat(task.getStatus()).isEqualTo(McpTaskStatus.CANCELLED);
    verify(gatewayOperations).cancel(eq(
        new McpGatewayCancellationRequest(
            "operation-1",
            "MCP Task cancelled by requestor"
        )
    ));
    assertThatThrownBy(() ->
        service.cancel(request("subject-1", "client-1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("terminal");
  }

  @Test
  void hidesTasksFromAnotherOauthSubject() {
    AiMcpTask task = task();
    when(repository.findActiveById("task-1"))
        .thenReturn(Optional.of(task));

    assertThatThrownBy(() ->
        service.find(request("subject-2", "client-1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("MCP Task does not exist");
  }

  private static McpPublicationTaskRequest request(
      final String subject,
      final String clientId
  ) {
    return new McpPublicationTaskRequest(
        "demo",
        "task-1",
        subject,
        clientId
    );
  }

  private static AiMcpTask task() {
    AiMcpTask task = new AiMcpTask();
    task.setId("task-1");
    task.setPublicationCode("demo");
    task.setToolName("echo");
    task.setSubject("subject-1");
    task.setSubjectHash(sha256("subject-1"));
    task.setClientId("client-1");
    task.setClientHash(sha256("client-1"));
    task.setSourceSessionId("session-1");
    task.setOperationId("operation-1");
    task.setArgumentsJson("{\"message\":\"hello\"}");
    task.setStatus(McpTaskStatus.PENDING);
    task.setStatusMessage("queued");
    task.setTtlMillis(60_000L);
    task.setPollIntervalMillis(1_000L);
    task.setExpiresAt(Instant.now().plusSeconds(60));
    task.setNextAttemptAt(Instant.now());
    task.setLeaseToken(0L);
    task.setAttemptCount(0);
    task.prePersist();
    return task;
  }

  private static String sha256(final String value) {
    try {
      byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static McpPublicationManifest manifest() {
    return new McpPublicationManifest(
        "demo",
        "Demo",
        "Demo",
        "https://example.test/mcp/demo",
        "https://auth.example.test",
        List.of(),
        60,
        "server-1",
        "snapshot-1",
        "2025-11-25",
        List.of(new McpToolDescriptor(
            "echo",
            "Echo",
            "Echo",
            Map.of("type", "object"),
            Map.of("type", "object"),
            Map.of(),
            List.of()
        )),
        List.of(),
        List.of(),
        List.of()
    );
  }
}
