package org.simplepoint.plugin.ai.skill.service.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecution;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecutionEvent;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionEventType;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillExecutionEventRepository;
import org.springframework.stereotype.Service;

/** Persists bounded append-only Skill lifecycle events. */
@Service
public class AiSkillExecutionEventPublisher {

  private static final int MAXIMUM_PAYLOAD_BYTES = 16 * 1024;

  private static final TypeReference<Map<String, Object>> MAP_TYPE =
      new TypeReference<>() {
      };

  private final AiSkillExecutionEventRepository repository;

  private final ObjectMapper objectMapper;

  /** Creates the Skill event publisher. */
  public AiSkillExecutionEventPublisher(
      final AiSkillExecutionEventRepository repository,
      final ObjectMapper objectMapper
  ) {
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  /** Appends an event while the owning execution row is locked. */
  public AiSkillExecutionEvent publish(
      final AiSkillExecution execution,
      final SkillExecutionEventType type,
      final String stepId,
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
          "Skill execution event payload is too large"
      );
    }
    AiSkillExecutionEvent event = new AiSkillExecutionEvent();
    event.setSkillId(execution.getSkillId());
    event.setExecutionId(execution.getId());
    event.setScopeType(execution.getScopeType());
    event.setTenantId(execution.getTenantId());
    event.setSequence(repository.findMaximumSequence(execution.getId()) + 1);
    event.setType(type);
    event.setExecutionStatus(execution.getStatus());
    event.setStepId(stepId);
    event.setActorId(actorId);
    event.setOccurredAt(occurredAt == null ? Instant.now() : occurredAt);
    event.setPayloadJson(payloadJson);
    event.setPayload(safePayload);
    return repository.save(event);
  }

  /** Decodes one stored event for a REST response. */
  public AiSkillExecutionEvent decorate(
      final AiSkillExecutionEvent event
  ) {
    event.setPayload(read(event.getPayloadJson()));
    return event;
  }

  private String write(final Map<String, Object> value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException(
          "Skill execution event payload is not serializable",
          exception
      );
    }
  }

  private Map<String, Object> read(final String value) {
    if (value == null || value.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(value, MAP_TYPE);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(
          "Stored Skill execution event payload is invalid",
          exception
      );
    }
  }
}
