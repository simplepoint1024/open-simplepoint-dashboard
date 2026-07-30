package org.simplepoint.plugin.ai.agent.service.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Map;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecution;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecutionEvent;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionEventType;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentExecutionEventRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Appends durable execution events and mirrors bounded event counts to Micrometer.
 */
@Service
public class AiAgentExecutionEventPublisher {

  private static final int MAXIMUM_PAYLOAD_CHARACTERS = 32_768;

  private final AiAgentExecutionEventRepository repository;

  private final ObjectMapper objectMapper;

  private final MeterRegistry meterRegistry;

  /**
   * Creates the event publisher.
   */
  public AiAgentExecutionEventPublisher(
      final AiAgentExecutionEventRepository repository,
      final ObjectMapper objectMapper,
      final ObjectProvider<MeterRegistry> meterRegistry
  ) {
    this.repository = repository;
    this.objectMapper = objectMapper;
    this.meterRegistry = meterRegistry.getIfAvailable();
  }

  /**
   * Appends one event inside the caller's execution-row transaction.
   */
  public AiAgentExecutionEvent publish(
      final AiAgentExecution execution,
      final AgentExecutionEventType type,
      final String traceId,
      final String interventionId,
      final String actorId,
      final Map<String, Object> payload,
      final Instant occurredAt
  ) {
    long count = repository.countActiveByExecutionId(execution.getId());
    if (count >= Integer.MAX_VALUE) {
      throw new IllegalStateException(
          "Agent execution has too many events"
      );
    }
    AiAgentExecutionEvent event = new AiAgentExecutionEvent();
    event.setAgentId(execution.getAgentId());
    event.setAgentVersionId(execution.getAgentVersionId());
    event.setExecutionId(execution.getId());
    event.setScopeType(execution.getScopeType());
    event.setTenantId(execution.getTenantId());
    event.setSequence((int) count);
    event.setType(type);
    event.setExecutionStatus(execution.getStatus());
    event.setTraceId(traceId);
    event.setInterventionId(interventionId);
    event.setActorId(actorId);
    event.setOccurredAt(occurredAt == null ? Instant.now() : occurredAt);
    event.setPayloadJson(writePayload(payload));
    AiAgentExecutionEvent saved = repository.save(event);
    recordMetric(saved);
    return saved;
  }

  private String writePayload(final Map<String, Object> payload) {
    if (payload == null || payload.isEmpty()) {
      return null;
    }
    try {
      String json = objectMapper.writeValueAsString(payload);
      if (json.length() > MAXIMUM_PAYLOAD_CHARACTERS) {
        throw new IllegalArgumentException(
            "Agent execution event payload is too large"
        );
      }
      return json;
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(
          "Agent execution event payload cannot be serialized",
          ex
      );
    }
  }

  private void recordMetric(final AiAgentExecutionEvent event) {
    if (meterRegistry == null) {
      return;
    }
    Counter.builder("simplepoint.agent.events")
        .description("Durable Agent execution events")
        .tag("type", event.getType().name())
        .tag("status", event.getExecutionStatus().name())
        .register(meterRegistry)
        .increment();
  }
}
