package org.simplepoint.plugin.ai.skill.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecution;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecutionEvent;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionEventType;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStatus;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillExecutionEventRepository;

@ExtendWith(MockitoExtension.class)
class AiSkillExecutionEventPublisherTest {

  @Mock
  private AiSkillExecutionEventRepository repository;

  private AiSkillExecutionEventPublisher publisher;

  @BeforeEach
  void setUp() {
    publisher = new AiSkillExecutionEventPublisher(
        repository,
        new ObjectMapper()
    );
  }

  @Test
  void appendsNextSequenceAndRestoresSafePayload() {
    AiSkillExecution execution = execution();
    Instant occurredAt = Instant.parse("2026-08-07T01:02:03Z");
    when(repository.findMaximumSequence("execution-a")).thenReturn(4L);
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    AiSkillExecutionEvent event = publisher.publish(
        execution,
        SkillExecutionEventType.STEP_STARTED,
        "step-a",
        "worker-a",
        Map.of("attempt", 2, "capabilityAlias", "echo"),
        occurredAt
    );

    assertThat(event.getSequence()).isEqualTo(5L);
    assertThat(event.getSkillId()).isEqualTo("skill-a");
    assertThat(event.getExecutionId()).isEqualTo("execution-a");
    assertThat(event.getExecutionStatus())
        .isEqualTo(SkillExecutionStatus.RUNNING);
    assertThat(event.getStepId()).isEqualTo("step-a");
    assertThat(event.getOccurredAt()).isEqualTo(occurredAt);
    assertThat(event.getPayloadJson()).doesNotContain("input", "output");

    event.setPayload(null);
    assertThat(publisher.decorate(event).getPayload())
        .containsEntry("attempt", 2)
        .containsEntry("capabilityAlias", "echo");
  }

  @Test
  void rejectsUnboundedEventPayload() {
    AiSkillExecution execution = execution();

    assertThatThrownBy(() -> publisher.publish(
        execution,
        SkillExecutionEventType.EXECUTION_FAILED,
        null,
        null,
        Map.of("error", "x".repeat(17 * 1024)),
        Instant.now()
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("too large");
  }

  private static AiSkillExecution execution() {
    AiSkillExecution execution = new AiSkillExecution();
    execution.setId("execution-a");
    execution.setSkillId("skill-a");
    execution.setScopeType(AiResourceScope.SYSTEM);
    execution.setStatus(SkillExecutionStatus.RUNNING);
    return execution;
  }
}
