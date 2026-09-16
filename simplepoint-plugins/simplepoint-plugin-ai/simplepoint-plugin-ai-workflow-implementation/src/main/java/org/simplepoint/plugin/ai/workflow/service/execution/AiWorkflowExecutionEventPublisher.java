package org.simplepoint.plugin.ai.workflow.service.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.simplepoint.plugin.ai.core.service.support.AiExecutionDiagnostics;
import org.simplepoint.plugin.ai.core.service.support.AiExecutionDiagnostics.StableDiagnostic;
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
    Map<String, Object> safePayload = safePayload(type, payload);
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
    event.setPayload(safePayload(
        event.getType(),
        read(event.getPayloadJson())
    ));
    return event;
  }

  private static Map<String, Object> safePayload(
      final WorkflowExecutionEventType type,
      final Map<String, Object> payload
  ) {
    Map<String, Object> source = payload == null
        ? Map.of() : new LinkedHashMap<>(payload);
    if (type == WorkflowExecutionEventType.EXECUTION_FAILED) {
      StableDiagnostic diagnostic =
          AiExecutionDiagnostics.workflowExecution(
              diagnosticCode(source)
          );
      return Map.of("code", diagnostic.errorCode());
    }
    if (type == WorkflowExecutionEventType.NODE_FAILED
        || type == WorkflowExecutionEventType.COMPENSATION_FAILED) {
      StableDiagnostic diagnostic = AiExecutionDiagnostics.workflowNode(
          diagnosticCode(source)
      );
      return withNodeId(source, "code", diagnostic.errorCode());
    }
    if (type == WorkflowExecutionEventType.NODE_CANCELLED
        || type == WorkflowExecutionEventType.HUMAN_TASK_CANCELLED) {
      return withNodeId(
          source,
          "reason",
          "WORKFLOW_NODE_CANCELLED"
      );
    }
    if (type == WorkflowExecutionEventType.COMPENSATION_STARTED) {
      if (source.containsKey("cause")) {
        StableDiagnostic diagnostic =
            AiExecutionDiagnostics.workflowExecution(
                text(source.get("cause"))
            );
        return Map.of("cause", diagnostic.errorCode());
      }
      Map<String, Object> identifiers = new LinkedHashMap<>();
      if (source.get("nodeId") != null) {
        identifiers.put("nodeId", source.get("nodeId"));
      }
      if (source.get("childExecutionId") != null) {
        identifiers.put(
            "childExecutionId",
            source.get("childExecutionId")
        );
      }
      return Map.copyOf(identifiers);
    }
    return source;
  }

  private static Map<String, Object> withNodeId(
      final Map<String, Object> source,
      final String diagnosticKey,
      final String diagnosticCode
  ) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (source.get("nodeId") != null) {
      result.put("nodeId", source.get("nodeId"));
    }
    result.put(diagnosticKey, diagnosticCode);
    return Map.copyOf(result);
  }

  private static String diagnosticCode(
      final Map<String, Object> payload
  ) {
    Object value = payload.get("code");
    if (value == null) {
      value = payload.get("errorCode");
    }
    return text(value);
  }

  private static String text(final Object value) {
    return value == null ? null : String.valueOf(value);
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
