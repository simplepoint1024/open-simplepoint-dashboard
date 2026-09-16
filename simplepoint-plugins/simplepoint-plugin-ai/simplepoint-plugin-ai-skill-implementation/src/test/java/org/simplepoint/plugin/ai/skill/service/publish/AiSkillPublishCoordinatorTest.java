package org.simplepoint.plugin.ai.skill.service.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillPublishTask;
import org.simplepoint.plugin.ai.skill.api.model.SkillPublishTaskStage;
import org.simplepoint.plugin.ai.skill.api.model.SkillPublishTaskStatus;
import org.simplepoint.plugin.ai.skill.api.properties.SkillPublishProperties;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillPublishTaskRepository;
import org.simplepoint.plugin.ai.skill.service.publish.AiSkillPublishCoordinator.PublishWork;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class AiSkillPublishCoordinatorTest {

  @Mock
  private AiSkillPublishTaskRepository repository;

  private SkillPublishProperties properties;

  private AiSkillPublishCoordinator coordinator;

  @BeforeEach
  void setUp() {
    properties = new SkillPublishProperties();
    properties.setLeaseDuration(Duration.ofMinutes(2));
    properties.setMaximumAttempts(3);
    coordinator = new AiSkillPublishCoordinator(
        repository,
        properties,
        new ObjectMapper(),
        new AiSkillPublishMetrics(new SimpleMeterRegistry())
    );
  }

  @Test
  void reclaimsExpiredRunningTaskWithNewFenceToken() {
    AiSkillPublishTask task = task();
    task.setStatus(SkillPublishTaskStatus.RUNNING);
    task.setAttemptCount(1);
    task.setLeaseOwner("dead-worker");
    task.setLeaseToken(2L);
    task.setLeaseExpiresAt(Instant.now().minusSeconds(10));
    when(repository.findClaimableForUpdate(any(), any(Pageable.class)))
        .thenReturn(List.of(task));

    List<PublishWork> result = coordinator.claim("worker-b");

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().leaseToken()).isEqualTo(3);
    assertThat(task.getAttemptCount()).isEqualTo(2);
    assertThat(task.getLeaseOwner()).isEqualTo("worker-b");
    assertThat(task.getStage()).isEqualTo(SkillPublishTaskStage.GENERATING);
    verify(repository).save(task);
  }

  @Test
  void marksRetryBudgetExhaustedWithoutExecutingNetworkWork() {
    AiSkillPublishTask task = task();
    task.setStatus(SkillPublishTaskStatus.PENDING);
    task.setAttemptCount(3);
    when(repository.findClaimableForUpdate(any(), any(Pageable.class)))
        .thenReturn(List.of(task));

    assertThat(coordinator.claim("worker-b")).isEmpty();
    assertThat(task.getStatus()).isEqualTo(SkillPublishTaskStatus.FAILED);
    assertThat(task.getErrorCode())
        .isEqualTo("SKILL_PUBLISH_RETRY_EXHAUSTED");
    assertThat(task.getCompletedAt()).isNotNull();
  }

  @Test
  void isolatesCorruptManifestWithoutPoisoningOtherClaimableTasks() {
    AiSkillPublishTask corrupt = task();
    corrupt.setId("task-corrupt");
    corrupt.setManifestJson("{not-json}");
    AiSkillPublishTask healthy = task();
    healthy.setId("task-healthy");
    when(repository.findClaimableForUpdate(any(), any(Pageable.class)))
        .thenReturn(List.of(corrupt, healthy));

    List<PublishWork> result = coordinator.claim("worker-b");

    assertThat(result).singleElement()
        .extracting(PublishWork::taskId)
        .isEqualTo("task-healthy");
    assertThat(corrupt.getStatus()).isEqualTo(SkillPublishTaskStatus.FAILED);
    assertThat(corrupt.getErrorCode())
        .isEqualTo("SKILL_PUBLISH_MANIFEST_INVALID");
    assertThat(corrupt.getLeaseOwner()).isNull();
  }

  private AiSkillPublishTask task() {
    AiSkillPublishTask task = new AiSkillPublishTask();
    task.setId("task-a");
    task.setSkillId("skill-a");
    task.setDraftId("draft-a");
    task.setDraftRevision(4L);
    task.setScopeType(AiResourceScope.SYSTEM);
    task.setVersion("2.0.0");
    task.setActivate(true);
    task.setManifestJson("""
        {"metadata":{"name":"document-summary"},"spec":{}}
        """);
    task.setStage(SkillPublishTaskStage.QUEUED);
    task.setAttemptCount(0);
    task.setLeaseToken(0L);
    task.setNextAttemptAt(Instant.now());
    return task;
  }
}
