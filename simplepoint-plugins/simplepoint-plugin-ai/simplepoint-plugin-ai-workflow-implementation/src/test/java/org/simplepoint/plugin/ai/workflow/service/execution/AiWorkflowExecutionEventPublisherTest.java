package org.simplepoint.plugin.ai.workflow.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowExecution;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowExecutionEvent;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionEventType;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionStatus;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowExecutionEventRepository;

class AiWorkflowExecutionEventPublisherTest {

  private AiWorkflowExecutionEventRepository repository;

  private AiWorkflowExecutionEventPublisher publisher;

  @BeforeEach
  void setUp() {
    repository = mock(AiWorkflowExecutionEventRepository.class);
    when(repository.save(any(AiWorkflowExecutionEvent.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    publisher = new AiWorkflowExecutionEventPublisher(
        repository,
        JsonMapper.builder().build()
    );
  }

  @Test
  void filtersNewFailureEventPayloadBeforePersistence() {
    final String sentinel =
        "provider body token=sk-live-event https://internal.example";
    AiWorkflowExecution execution = new AiWorkflowExecution();
    execution.setId("execution-1");
    execution.setStatus(WorkflowExecutionStatus.FAILED);
    when(repository.findMaximumSequence("execution-1")).thenReturn(7L);

    AiWorkflowExecutionEvent event = publisher.publish(
        execution,
        WorkflowExecutionEventType.EXECUTION_FAILED,
        null,
        null,
        "workflow-runtime",
        Map.of(
            "code", "UPSTREAM_FAILURE",
            "errorMessage", sentinel
        ),
        Instant.now()
    );

    assertThat(event.getSequence()).isEqualTo(8L);
    assertThat(event.getPayload()).containsExactly(
        Map.entry("code", "WORKFLOW_EXECUTION_FAILED")
    );
    assertThat(event.getPayloadJson())
        .contains("WORKFLOW_EXECUTION_FAILED")
        .doesNotContain(sentinel);
  }

  @Test
  void filtersHistoricalNodeFailureEventWithoutLosingIdentifiers() {
    String sentinel = "Bearer sk-live-history at https://internal.example";
    AiWorkflowExecutionEvent event = new AiWorkflowExecutionEvent();
    event.setId("event-history");
    event.setType(WorkflowExecutionEventType.NODE_FAILED);
    event.setPayloadJson("{\"nodeId\":\"node-1\","
        + "\"code\":\"UPSTREAM_FAILURE\","
        + "\"errorMessage\":\"" + sentinel + "\"}");

    AiWorkflowExecutionEvent result = publisher.decorate(event);

    assertThat(result.getId()).isEqualTo("event-history");
    assertThat(result.getPayload()).containsExactlyInAnyOrderEntriesOf(
        Map.of(
            "nodeId", "node-1",
            "code", "WORKFLOW_NODE_FAILED"
        )
    );
    assertThat(String.valueOf(result.getPayload())).doesNotContain(sentinel);
  }

  @Test
  void preservesNodeCompensationIdentifiersWithoutInventingFailureCause() {
    AiWorkflowExecution execution = new AiWorkflowExecution();
    execution.setId("execution-1");
    execution.setStatus(WorkflowExecutionStatus.COMPENSATING);
    when(repository.findMaximumSequence("execution-1")).thenReturn(3L);

    AiWorkflowExecutionEvent event = publisher.publish(
        execution,
        WorkflowExecutionEventType.COMPENSATION_STARTED,
        "node-execution-1",
        null,
        "workflow-runtime",
        Map.of(
            "nodeId", "node-1",
            "childExecutionId", "compensation-execution-1"
        ),
        Instant.now()
    );

    assertThat(event.getPayload()).containsExactlyInAnyOrderEntriesOf(
        Map.of(
            "nodeId", "node-1",
            "childExecutionId", "compensation-execution-1"
        )
    );
    assertThat(event.getPayload()).doesNotContainKey("cause");
  }

  @Test
  void classifiesHistoricalCancellationAsCancellationNotFailure() {
    String sentinel = "operator cancellation token=sk-live-cancel";
    AiWorkflowExecutionEvent event = new AiWorkflowExecutionEvent();
    event.setId("event-cancel-history");
    event.setType(WorkflowExecutionEventType.NODE_CANCELLED);
    event.setPayloadJson("{\"nodeId\":\"node-1\","
        + "\"reason\":\"" + sentinel + "\"}");

    AiWorkflowExecutionEvent result = publisher.decorate(event);

    assertThat(result.getPayload()).containsExactlyInAnyOrderEntriesOf(
        Map.of(
            "nodeId", "node-1",
            "reason", "WORKFLOW_NODE_CANCELLED"
        )
    );
    assertThat(String.valueOf(result.getPayload())).doesNotContain(sentinel);
  }
}
