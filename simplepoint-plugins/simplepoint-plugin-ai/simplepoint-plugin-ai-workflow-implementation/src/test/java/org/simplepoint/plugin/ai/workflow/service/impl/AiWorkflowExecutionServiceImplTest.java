package org.simplepoint.plugin.ai.workflow.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.simplepoint.plugin.ai.skill.service.support.SkillJsonSchemaValidator;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowDefinition;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowExecution;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowNodeExecution;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowVersion;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionStartRequest;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionStatus;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowNodeExecutionStatus;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowStatus;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowVersionStatus;
import org.simplepoint.plugin.ai.workflow.api.properties.WorkflowExecutionProperties;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowDefinitionRepository;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowDependencyBindingRepository;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowExecutionEventRepository;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowExecutionRepository;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowHumanTaskRepository;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowNodeExecutionRepository;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowVersionRepository;
import org.simplepoint.plugin.ai.workflow.service.execution.AiWorkflowExecutionEventPublisher;
import org.simplepoint.plugin.ai.workflow.service.execution.AiWorkflowTerminationCoordinator;
import org.simplepoint.plugin.ai.workflow.service.support.WorkflowManifestCompiler;

class AiWorkflowExecutionServiceImplTest {

  private AiWorkflowDefinitionRepository workflowRepository;

  private AiWorkflowExecutionRepository executionRepository;

  private AiWorkflowNodeExecutionRepository nodeRepository;

  private AiWorkflowExecutionEventPublisher eventPublisher;

  private AiWorkflowTerminationCoordinator terminationCoordinator;

  private AiWorkflowExecutionServiceImpl service;

  @BeforeEach
  void setUp() {
    workflowRepository = mock(AiWorkflowDefinitionRepository.class);
    final AiWorkflowVersionRepository versionRepository =
        mock(AiWorkflowVersionRepository.class);
    final AiWorkflowDependencyBindingRepository bindingRepository =
        mock(AiWorkflowDependencyBindingRepository.class);
    executionRepository = mock(AiWorkflowExecutionRepository.class);
    nodeRepository = mock(AiWorkflowNodeExecutionRepository.class);
    final AiWorkflowHumanTaskRepository taskRepository =
        mock(AiWorkflowHumanTaskRepository.class);
    final AiScopeAccessPolicy scopeAccessPolicy =
        mock(AiScopeAccessPolicy.class);
    eventPublisher = mock(AiWorkflowExecutionEventPublisher.class);
    terminationCoordinator = mock(AiWorkflowTerminationCoordinator.class);

    AiWorkflowDefinition workflow = new AiWorkflowDefinition();
    workflow.setId("workflow-1");
    workflow.setScopeType(AiResourceScope.SYSTEM);
    workflow.setEnabled(true);
    workflow.setStatus(WorkflowStatus.ACTIVE);
    workflow.setActiveVersionId("version-1");
    when(workflowRepository.findActiveByIdForUpdate("workflow-1"))
        .thenReturn(Optional.of(workflow));
    when(workflowRepository.findActiveById("workflow-1"))
        .thenReturn(Optional.of(workflow));
    AiWorkflowVersion version = new AiWorkflowVersion();
    version.setId("version-1");
    version.setWorkflowId("workflow-1");
    version.setStatus(WorkflowVersionStatus.PUBLISHED);
    version.setContentHash("a".repeat(64));
    version.setManifestJson("""
        {"spec":{
          "inputSchema":{"type":"object","additionalProperties":true},
          "outputSchema":{"type":"object","additionalProperties":true},
          "nodes":[{"id":"done","type":"end"}],"edges":[]
        }}
        """);
    when(versionRepository.findActiveById("version-1"))
        .thenReturn(Optional.of(version));
    when(bindingRepository.findAllActiveByWorkflowVersionId("version-1"))
        .thenReturn(List.of());
    when(nodeRepository.save(any(AiWorkflowNodeExecution.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(nodeRepository.findAllActiveByExecutionId(any()))
        .thenReturn(List.of());
    when(taskRepository.findAllActiveByExecutionId(any()))
        .thenReturn(List.of());
    when(scopeAccessPolicy.currentManagementScope())
        .thenReturn(new ScopeAssignment(AiResourceScope.SYSTEM, null));

    service = new AiWorkflowExecutionServiceImpl(
        workflowRepository,
        versionRepository,
        bindingRepository,
        executionRepository,
        nodeRepository,
        taskRepository,
        mock(AiWorkflowExecutionEventRepository.class),
        eventPublisher,
        terminationCoordinator,
        scopeAccessPolicy,
        new SkillJsonSchemaValidator(),
        new WorkflowManifestCompiler(),
        new WorkflowExecutionProperties(),
        JsonMapper.builder().build()
    );
  }

  @Test
  void serializesStartsAndReturnsExistingIdempotentExecution() {
    AtomicReference<AiWorkflowExecution> persisted = new AtomicReference<>();
    when(executionRepository.findActiveByIdempotency(any(), any(), any(), any()))
        .thenAnswer(invocation -> Optional.ofNullable(persisted.get()));
    when(executionRepository.save(any(AiWorkflowExecution.class)))
        .thenAnswer(invocation -> {
          AiWorkflowExecution execution = invocation.getArgument(0);
          execution.setId("execution-idempotent");
          persisted.set(execution);
          return execution;
        });
    WorkflowExecutionStartRequest request = new WorkflowExecutionStartRequest(
        "stable-request-key", Map.of("request", "hello"));

    AiWorkflowExecution first = service.start("workflow-1", request);
    AiWorkflowExecution replay = service.start("workflow-1", request);

    assertThat(replay.getId()).isEqualTo(first.getId());
    verify(workflowRepository, times(2)).findActiveByIdForUpdate("workflow-1");
    verify(executionRepository).save(any(AiWorkflowExecution.class));
  }

  @Test
  void rejectsIdempotencyKeyReusedWithDifferentInput() {
    AtomicReference<AiWorkflowExecution> persisted = new AtomicReference<>();
    when(executionRepository.findActiveByIdempotency(any(), any(), any(), any()))
        .thenAnswer(invocation -> Optional.ofNullable(persisted.get()));
    when(executionRepository.save(any(AiWorkflowExecution.class)))
        .thenAnswer(invocation -> {
          AiWorkflowExecution execution = invocation.getArgument(0);
          execution.setId("execution-idempotent");
          persisted.set(execution);
          return execution;
        });
    service.start("workflow-1", new WorkflowExecutionStartRequest(
        "stable-request-key", Map.of("request", "first")));

    assertThatThrownBy(() -> service.start(
        "workflow-1",
        new WorkflowExecutionStartRequest(
            "stable-request-key", Map.of("request", "different"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("different input");
    verify(executionRepository).save(any(AiWorkflowExecution.class));
  }

  @Test
  void cancelRoutesEveryUnfinishedCheckpointThroughCoordinator() {
    AiWorkflowExecution execution = visibleExecution(
        WorkflowExecutionStatus.WAITING_CHILD
    );
    AiWorkflowNodeExecution node = new AiWorkflowNodeExecution();
    node.setId("node-execution-1");
    node.setExecutionId(execution.getId());
    node.setNodeId("node-1");
    node.setNodeOrder(0);
    node.setNodeType("skill");
    node.setStatus(
        org.simplepoint.plugin.ai.workflow.api.model
            .WorkflowNodeExecutionStatus.WAITING
    );
    when(executionRepository.findActiveByIdForUpdate("execution-1"))
        .thenReturn(Optional.of(execution));
    when(nodeRepository.findAllActiveByExecutionId("execution-1"))
        .thenReturn(List.of(node));
    when(executionRepository.save(execution)).thenReturn(execution);

    AiWorkflowExecution result = service.cancel(
        "workflow-1", "execution-1"
    );

    assertThat(result.getStatus()).isEqualTo(WorkflowExecutionStatus.CANCELLED);
    assertThat(result.getCompletedAt()).isNotNull();
    verify(terminationCoordinator).terminate(
        org.mockito.ArgumentMatchers.eq(execution),
        org.mockito.ArgumentMatchers.eq(List.of(node)),
        org.mockito.ArgumentMatchers.eq("system"),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any()
    );
    verify(eventPublisher).publish(
        org.mockito.ArgumentMatchers.eq(execution),
        org.mockito.ArgumentMatchers.eq(
            org.simplepoint.plugin.ai.workflow.api.model
                .WorkflowExecutionEventType.EXECUTION_CANCELLED
        ),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.eq("system"),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any()
    );
    verify(executionRepository).save(execution);
  }

  @Test
  void repeatedCancelIsIdempotentWithoutDuplicateTerminationOrEvent() {
    AiWorkflowExecution execution = visibleExecution(
        WorkflowExecutionStatus.CANCELLED
    );
    when(executionRepository.findActiveByIdForUpdate("execution-1"))
        .thenReturn(Optional.of(execution));

    AiWorkflowExecution result = service.cancel(
        "workflow-1", "execution-1"
    );

    assertThat(result).isSameAs(execution);
    verify(terminationCoordinator, never()).terminate(
        any(), any(), any(), any(), any()
    );
    verify(eventPublisher, never()).publish(
        any(), any(), any(), any(), any(), any(), any()
    );
    verify(executionRepository, never()).save(execution);
  }

  @Test
  void cancelStillRejectsSuccessfulAndFailedExecutions() {
    for (WorkflowExecutionStatus terminal : List.of(
        WorkflowExecutionStatus.SUCCEEDED,
        WorkflowExecutionStatus.FAILED
    )) {
      AiWorkflowExecution execution = visibleExecution(terminal);
      when(executionRepository.findActiveByIdForUpdate("execution-1"))
          .thenReturn(Optional.of(execution));

      assertThatThrownBy(() -> service.cancel(
          "workflow-1", "execution-1"
      )).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("already terminal");
    }
    verify(terminationCoordinator, never()).terminate(
        any(), any(), any(), any(), any()
    );
  }

  @Test
  void filtersHistoricalExecutionAndNodeDiagnosticsFromResponses() {
    String sentinel =
        "provider body token=sk-live-history https://internal.example";
    AiWorkflowExecution execution = visibleExecution(
        WorkflowExecutionStatus.FAILED
    );
    execution.setErrorCode("UPSTREAM_" + sentinel);
    execution.setErrorMessage(sentinel);
    AiWorkflowNodeExecution node = new AiWorkflowNodeExecution();
    node.setId("node-execution-history");
    node.setExecutionId(execution.getId());
    node.setNodeId("node-history");
    node.setStatus(WorkflowNodeExecutionStatus.FAILED);
    node.setErrorCode("INTERNAL_" + sentinel);
    node.setErrorMessage(sentinel);
    when(executionRepository.findActiveById("execution-1"))
        .thenReturn(Optional.of(execution));
    when(nodeRepository.findAllActiveByExecutionId("execution-1"))
        .thenReturn(List.of(node));

    AiWorkflowExecution result = service.find(
        "workflow-1",
        "execution-1"
    ).orElseThrow();

    assertThat(result.getId()).isEqualTo("execution-1");
    assertThat(result.getErrorCode())
        .isEqualTo("WORKFLOW_EXECUTION_FAILED");
    assertThat(result.getErrorMessage())
        .isEqualTo("WORKFLOW_EXECUTION_FAILED");
    assertThat(result.getNodes()).singleElement().satisfies(item -> {
      assertThat(item.getId()).isEqualTo("node-execution-history");
      assertThat(item.getErrorCode()).isEqualTo("WORKFLOW_NODE_FAILED");
      assertThat(item.getErrorMessage()).isEqualTo("WORKFLOW_NODE_FAILED");
      assertThat(item.getOutput()).isNull();
    });
  }

  @Test
  void preservesForwardOutputWhileFilteringCompensationDiagnostic() {
    String sentinel = "compensation provider token=sk-live-compensation";
    AiWorkflowExecution execution = visibleExecution(
        WorkflowExecutionStatus.FAILED
    );
    execution.setErrorCode("WORKFLOW_EXECUTION_FAILED");
    execution.setErrorMessage(sentinel);
    AiWorkflowNodeExecution node = new AiWorkflowNodeExecution();
    node.setId("node-compensation-history");
    node.setExecutionId(execution.getId());
    node.setNodeId("node-compensation");
    node.setStatus(WorkflowNodeExecutionStatus.COMPENSATION_FAILED);
    node.setErrorCode("COMPENSATION_UPSTREAM_FAILURE");
    node.setErrorMessage(sentinel);
    node.setOutputJson("{\"forwardResult\":42}");
    when(executionRepository.findActiveById("execution-1"))
        .thenReturn(Optional.of(execution));
    when(nodeRepository.findAllActiveByExecutionId("execution-1"))
        .thenReturn(List.of(node));

    AiWorkflowNodeExecution result = service.find(
        "workflow-1",
        "execution-1"
    ).orElseThrow().getNodes().getFirst();

    assertThat(result.getId()).isEqualTo("node-compensation-history");
    assertThat(result.getErrorCode()).isEqualTo("WORKFLOW_NODE_FAILED");
    assertThat(result.getErrorMessage()).isEqualTo("WORKFLOW_NODE_FAILED");
    assertThat(result.getOutput()).isEqualTo(Map.of("forwardResult", 42));
    assertThat(String.valueOf(result.getOutput())).doesNotContain(sentinel);
  }

  private static AiWorkflowExecution visibleExecution(
      final WorkflowExecutionStatus status
  ) {
    AiWorkflowExecution execution = new AiWorkflowExecution();
    execution.setId("execution-1");
    execution.setWorkflowId("workflow-1");
    execution.setScopeType(AiResourceScope.SYSTEM);
    execution.setStatus(status);
    execution.setInputJson("{}");
    return execution;
  }
}
