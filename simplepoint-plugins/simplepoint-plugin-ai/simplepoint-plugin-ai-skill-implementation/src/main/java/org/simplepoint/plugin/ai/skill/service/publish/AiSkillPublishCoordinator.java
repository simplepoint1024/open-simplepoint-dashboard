package org.simplepoint.plugin.ai.skill.service.publish;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillPublishTask;
import org.simplepoint.plugin.ai.skill.api.model.SkillPublishTaskStage;
import org.simplepoint.plugin.ai.skill.api.model.SkillPublishTaskStatus;
import org.simplepoint.plugin.ai.skill.api.properties.SkillPublishProperties;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillPublishTaskRepository;
import org.simplepoint.plugin.ai.skill.service.artifact.PushedSkillOciArtifact;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short fenced database transitions for durable Skill publications. */
@Slf4j
@Service
public class AiSkillPublishCoordinator {

  private static final TypeReference<Map<String, Object>> MAP_TYPE =
      new TypeReference<>() {
      };

  private final AiSkillPublishTaskRepository repository;

  private final SkillPublishProperties properties;

  private final ObjectMapper objectMapper;

  private final AiSkillPublishMetrics metrics;

  /** Creates the publication state coordinator. */
  public AiSkillPublishCoordinator(
      final AiSkillPublishTaskRepository repository,
      final SkillPublishProperties properties,
      final ObjectMapper objectMapper,
      final AiSkillPublishMetrics metrics
  ) {
    this.repository = repository;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.metrics = metrics;
  }

  /** Claims due and expired tasks with skip-locked fencing. */
  @Transactional(rollbackFor = Exception.class)
  public List<PublishWork> claim(final String workerId) {
    Instant now = Instant.now();
    List<AiSkillPublishTask> candidates = repository.findClaimableForUpdate(
        now,
        PageRequest.of(0, batchSize())
    );
    List<PublishWork> result = new ArrayList<>();
    for (AiSkillPublishTask task : candidates) {
      if (task.getAttemptCount() >= maximumAttempts()) {
        failExhausted(task, now);
        continue;
      }
      final boolean reclaimed =
          task.getStatus() == SkillPublishTaskStatus.RUNNING;
      task.setStatus(SkillPublishTaskStatus.RUNNING);
      task.setStage(SkillPublishTaskStage.GENERATING);
      task.setAttemptCount(task.getAttemptCount() + 1);
      task.setLeaseOwner(workerId);
      task.setLeaseToken(task.getLeaseToken() + 1);
      task.setLeaseExpiresAt(now.plus(leaseDuration()));
      task.setStartedAt(task.getStartedAt() == null
          ? now : task.getStartedAt());
      task.setCompletedAt(null);
      task.setErrorCode(null);
      task.setErrorMessage(null);
      repository.save(task);
      metrics.outcome(reclaimed ? "reclaimed" : "claimed");
      metrics.stage(SkillPublishTaskStage.GENERATING);
      try {
        result.add(toWork(task));
      } catch (IllegalStateException ex) {
        failInvalidManifest(task, now, ex);
      }
    }
    return List.copyOf(result);
  }

  /** Moves a claimed task to a new pre-I/O stage and extends its lease. */
  @Transactional(rollbackFor = Exception.class)
  public boolean checkpoint(
      final PublishWork work,
      final SkillPublishTaskStage stage
  ) {
    AiSkillPublishTask task = claimed(work);
    if (task == null) {
      return false;
    }
    task.setStage(stage);
    task.setLeaseExpiresAt(Instant.now().plus(leaseDuration()));
    repository.save(task);
    metrics.stage(stage);
    return true;
  }

  /** Persists the verified local/remote Artifact identity after push. */
  @Transactional(rollbackFor = Exception.class)
  public boolean pushed(
      final PublishWork work,
      final PushedSkillOciArtifact artifact
  ) {
    AiSkillPublishTask task = claimed(work);
    if (task == null) {
      return false;
    }
    task.setArtifactReference(artifact.artifactReference());
    task.setArtifactDigest(artifact.artifactDigest());
    task.setArtifactConfigDigest(artifact.configDigest());
    task.setArtifactContentDigest(artifact.contentDigest());
    task.setContentHash(artifact.contentHash());
    task.setStage(SkillPublishTaskStage.VERIFYING);
    task.setLeaseExpiresAt(Instant.now().plus(leaseDuration()));
    repository.save(task);
    metrics.stage(SkillPublishTaskStage.VERIFYING);
    return true;
  }

  /** Persists the immutable Skill Version checkpoint. */
  @Transactional(rollbackFor = Exception.class)
  public boolean versionCreated(
      final PublishWork work,
      final String versionId
  ) {
    AiSkillPublishTask task = claimed(work);
    if (task == null) {
      return false;
    }
    task.setSkillVersionId(versionId);
    task.setStage(task.getActivate()
        ? SkillPublishTaskStage.ACTIVATING
        : SkillPublishTaskStage.CREATING_VERSION);
    task.setLeaseExpiresAt(Instant.now().plus(leaseDuration()));
    repository.save(task);
    metrics.stage(task.getStage());
    return true;
  }

  /** Completes one fenced publication. */
  @Transactional(rollbackFor = Exception.class)
  public boolean succeed(final PublishWork work) {
    AiSkillPublishTask task = claimed(work);
    if (task == null) {
      return false;
    }
    task.setStatus(SkillPublishTaskStatus.SUCCEEDED);
    task.setStage(SkillPublishTaskStage.COMPLETED);
    Instant completedAt = Instant.now();
    task.setCompletedAt(completedAt);
    task.setErrorCode(null);
    task.setErrorMessage(null);
    clearLease(task);
    repository.save(task);
    metrics.stage(SkillPublishTaskStage.COMPLETED);
    metrics.outcome("succeeded");
    metrics.duration("succeeded", task.getStartedAt(), completedAt);
    return true;
  }

  /** Requeues a failed attempt or marks the retry budget exhausted. */
  @Transactional(rollbackFor = Exception.class)
  public void fail(final PublishWork work, final RuntimeException failure) {
    AiSkillPublishTask task = claimed(work);
    if (task == null) {
      return;
    }
    Instant now = Instant.now();
    task.setErrorMessage(safeMessage(failure));
    if (task.getAttemptCount() >= maximumAttempts()) {
      failExhausted(task, now);
    } else {
      task.setStatus(SkillPublishTaskStatus.PENDING);
      task.setErrorCode("SKILL_PUBLISH_ATTEMPT_FAILED");
      long delay = Math.min(30L, 1L << task.getAttemptCount());
      task.setNextAttemptAt(now.plusSeconds(delay));
      clearLease(task);
      repository.save(task);
      metrics.outcome("retry_scheduled");
      log.warn(
          "Skill publication attempt failed; taskId={}, attempt={}, "
              + "nextAttemptAt={}, error={}",
          task.getId(),
          task.getAttemptCount(),
          task.getNextAttemptAt(),
          task.getErrorMessage()
      );
    }
  }

  private AiSkillPublishTask claimed(final PublishWork work) {
    AiSkillPublishTask task = repository.findActiveByIdForUpdate(work.taskId())
        .orElse(null);
    if (task == null
        || task.getStatus() != SkillPublishTaskStatus.RUNNING
        || !Objects.equals(task.getLeaseToken(), work.leaseToken())
        || !Objects.equals(task.getLeaseOwner(), work.workerId())) {
      metrics.outcome("fenced");
      return null;
    }
    return task;
  }

  private PublishWork toWork(final AiSkillPublishTask task) {
    try {
      Map<String, Object> manifest = objectMapper.readValue(
          task.getManifestJson(),
          MAP_TYPE
      );
      if (!(manifest.get("metadata") instanceof Map<?, ?> metadata)) {
        throw new IllegalStateException(
            "Persisted Skill publication Manifest metadata is invalid"
        );
      }
      Object name = metadata.get("name");
      String skillCode = name == null ? "" : String.valueOf(name).trim();
      if (skillCode.isEmpty()) {
        throw new IllegalStateException(
            "Persisted Skill publication Manifest name is invalid"
        );
      }
      return new PublishWork(
          task.getId(),
          task.getLeaseToken(),
          task.getLeaseOwner(),
          task.getSkillId(),
          task.getScopeType(),
          task.getTenantId(),
          skillCode,
          task.getDraftRevision(),
          task.getVersion(),
          Boolean.TRUE.equals(task.getActivate()),
          manifest
      );
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(
          "Persisted Skill publication Manifest is invalid",
          ex
      );
    }
  }

  private void failInvalidManifest(
      final AiSkillPublishTask task,
      final Instant now,
      final IllegalStateException failure
  ) {
    task.setStatus(SkillPublishTaskStatus.FAILED);
    task.setErrorCode("SKILL_PUBLISH_MANIFEST_INVALID");
    task.setErrorMessage(safeMessage(failure));
    task.setCompletedAt(now);
    clearLease(task);
    repository.save(task);
    metrics.outcome("invalid_manifest");
    metrics.duration("failed", task.getStartedAt(), now);
    log.warn(
        "Skill publication rejected an invalid persisted Manifest; "
            + "taskId={}, errorCode={}, error={}",
        task.getId(),
        task.getErrorCode(),
        task.getErrorMessage()
    );
  }

  private void failExhausted(
      final AiSkillPublishTask task,
      final Instant now
  ) {
    task.setStatus(SkillPublishTaskStatus.FAILED);
    task.setErrorCode("SKILL_PUBLISH_RETRY_EXHAUSTED");
    if (task.getErrorMessage() == null) {
      task.setErrorMessage("Skill publication retry limit was exhausted");
    }
    task.setCompletedAt(now);
    clearLease(task);
    repository.save(task);
    metrics.outcome("retry_exhausted");
    metrics.duration("failed", task.getStartedAt(), now);
    log.warn(
        "Skill publication exhausted its retry budget; taskId={}, "
            + "attempts={}, error={}",
        task.getId(),
        task.getAttemptCount(),
        task.getErrorMessage()
    );
  }

  private int batchSize() {
    Integer value = properties.getBatchSize();
    return value == null ? 2 : Math.max(1, Math.min(value, 16));
  }

  private int maximumAttempts() {
    Integer value = properties.getMaximumAttempts();
    return value == null ? 3 : Math.max(1, Math.min(value, 10));
  }

  private Duration leaseDuration() {
    Duration value = properties.getLeaseDuration();
    if (value == null || value.isNegative() || value.isZero()) {
      return Duration.ofMinutes(2);
    }
    return value.compareTo(Duration.ofMinutes(10)) > 0
        ? Duration.ofMinutes(10) : value;
  }

  private void clearLease(final AiSkillPublishTask task) {
    task.setLeaseOwner(null);
    task.setLeaseExpiresAt(null);
  }

  private String safeMessage(final RuntimeException failure) {
    String message = failure == null ? null : failure.getMessage();
    if (message == null || message.isBlank()) {
      return "Skill publication failed";
    }
    return message.length() > 2048 ? message.substring(0, 2048) : message;
  }

  /** Immutable claimed work executed outside a database transaction. */
  public record PublishWork(
      String taskId,
      long leaseToken,
      String workerId,
      String skillId,
      AiResourceScope scopeType,
      String tenantId,
      String skillCode,
      long draftRevision,
      String version,
      boolean activate,
      Map<String, Object> manifest
  ) {
  }
}
