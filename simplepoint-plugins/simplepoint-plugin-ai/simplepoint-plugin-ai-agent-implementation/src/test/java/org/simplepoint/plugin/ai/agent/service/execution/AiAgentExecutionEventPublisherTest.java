package org.simplepoint.plugin.ai.agent.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecution;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecutionEvent;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionEventType;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionStatus;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentExecutionEventRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.springframework.beans.factory.ObjectProvider;

class AiAgentExecutionEventPublisherTest {

  @Test
  void appendsTheNextSequenceAndMirrorsBoundedMetric() {
    AiAgentExecutionEventRepository repository =
        mock(AiAgentExecutionEventRepository.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider =
        mock(ObjectProvider.class);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    when(provider.getIfAvailable()).thenReturn(registry);
    when(repository.countActiveByExecutionId("execution-1"))
        .thenReturn(3L);
    when(repository.save(any(AiAgentExecutionEvent.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    AiAgentExecution execution = new AiAgentExecution();
    execution.setId("execution-1");
    execution.setAgentId("agent-1");
    execution.setAgentVersionId("version-1");
    execution.setScopeType(AiResourceScope.TENANT);
    execution.setTenantId("tenant-1");
    execution.setStatus(AgentExecutionStatus.RUNNING);
    AiAgentExecutionEventPublisher publisher =
        new AiAgentExecutionEventPublisher(
            repository,
            JsonMapper.builder().build(),
            provider
        );
    Instant occurredAt = Instant.parse("2026-07-30T00:00:00Z");

    AiAgentExecutionEvent event = publisher.publish(
        execution,
        AgentExecutionEventType.MODEL_STARTED,
        "trace-1",
        null,
        "operator-1",
        Map.of("modelId", "model-1"),
        occurredAt
    );

    assertThat(event.getSequence()).isEqualTo(3);
    assertThat(event.getTenantId()).isEqualTo("tenant-1");
    assertThat(event.getTraceId()).isEqualTo("trace-1");
    assertThat(event.getOccurredAt()).isEqualTo(occurredAt);
    assertThat(event.getPayloadJson()).isEqualTo(
        "{\"modelId\":\"model-1\"}"
    );
    assertThat(registry.get("simplepoint.agent.events")
        .tags(
            "type",
            "MODEL_STARTED",
            "status",
            "RUNNING"
        )
        .counter()
        .count()).isEqualTo(1D);
  }

  @Test
  void filtersFailurePayloadBeforePersistence() {
    final String sentinel =
        "provider body token=sk-live-event https://internal.example";
    AiAgentExecutionEventRepository repository =
        mock(AiAgentExecutionEventRepository.class);
    @SuppressWarnings("unchecked")
    final ObjectProvider<io.micrometer.core.instrument.MeterRegistry>
        provider =
        mock(ObjectProvider.class);
    when(repository.save(any(AiAgentExecutionEvent.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    AiAgentExecution execution = new AiAgentExecution();
    execution.setId("execution-1");
    execution.setAgentId("agent-1");
    execution.setAgentVersionId("version-1");
    execution.setScopeType(AiResourceScope.SYSTEM);
    execution.setStatus(AgentExecutionStatus.FAILED);
    AiAgentExecutionEventPublisher publisher =
        new AiAgentExecutionEventPublisher(
            repository,
            JsonMapper.builder().build(),
            provider
        );

    AiAgentExecutionEvent event = publisher.publish(
        execution,
        AgentExecutionEventType.EXECUTION_FAILED,
        "trace-1",
        null,
        "agent-runtime",
        Map.of(
            "errorCode", "UPSTREAM_FAILURE",
            "errorMessage", sentinel
        ),
        Instant.now()
    );

    assertThat(event.getPayload()).containsExactly(
        Map.entry("errorCode", "AGENT_EXECUTION_FAILED")
    );
    assertThat(event.getPayloadJson())
        .contains("AGENT_EXECUTION_FAILED")
        .doesNotContain(sentinel);
  }
}
