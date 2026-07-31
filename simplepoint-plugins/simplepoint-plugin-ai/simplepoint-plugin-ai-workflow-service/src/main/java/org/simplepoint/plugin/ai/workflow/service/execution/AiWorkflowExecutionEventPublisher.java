package org.simplepoint.plugin.ai.workflow.service.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowExecution;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowExecutionEvent;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionEventType;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowExecutionEventRepository;
import org.springframework.stereotype.Service;

/**
 * Persists bounded append-only Workflow lifecycle events.
 */
@Service
public class AiWorkflowExecutionEventPublisher {

  private static final int MAXIMUM_PAYLOAD_BYTES = 16 * 1024;

  private static final TypeReference<Map<String, Object>> MAP_TYPE =
      new TypeReference<>() {
      };

  private final AiWorkflowExecutionEventRepository repository;

  private final ObjectMapper objectMapper;

  /**
   * Creates the Workflow event publisher.
   */
  public AiWorkflowExecutionEventPublisher(
      final AiWorkflowExecutionEventRepository repository,
      final ObjectMapper objectMapper
  ) {
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  /**
   * Appends one event while the owning execution is transactionally locked.
   */
  public AiWorkflowExecutionEvent publish(
      final AiWorkflowExecution execution,
      final WorkflowExecutionEventType type,
      final String nodeExecutionId,
      final String humanTaskId,
      final String actorId,
      final Map<String, Object> payload,
      final Instant occurredAt
  ) {
    Map<String, Object> safePayload = payload == null
        ? Map.of() : new LinkedHashMap<>(payload);
    String payloadJson = write(safePayload);
    if (payloadJson.getBytes(StandardCharsets.UTF_8).length
        > MAXIMUM_PAYLOAD_BYTES) {
      throw new IllegalArgumentException(
          "Workflow event payload is too large"
      );
    }
    AiWorkflowExecutionEvent event = new AiWorkflowExecutionEvent();
    event.setExecutionId(execution.getId());
    event.setSequence(
        repository.findMaximumSequence(execution.getId()) + 1
    );
    event.setType(type);
    event.setExecutionStatus(execution.getStatus());
    event.setNodeExecutionId(nodeExecutionId);
    event.setHumanTaskId(humanTaskId);
    event.setActorId(actorId);
    event.setOccurredAt(occurredAt == null ? Instant.now() : occurredAt);
    event.setPayloadJson(payloadJson);
    event.setPayload(safePayload);
    return repository.save(event);
  }

  /**
   * Decorates one stored event for a REST response.
   */
  public AiWorkflowExecutionEvent decorate(
      final AiWorkflowExecutionEvent event
  ) {
    event.setPayload(read(event.getPayloadJson()));
    return event;
  }

  private String write(final Map<String, Object> value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException(
          "Workflow event payload is not serializable",
          ex
      );
    }
  }

  private Map<String, Object> read(final String value) {
    if (value == null || value.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(value, MAP_TYPE);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(
          "Stored Workflow event payload is invalid",
          ex
      );
    }
  }
}
