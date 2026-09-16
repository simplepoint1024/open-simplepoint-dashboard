package org.simplepoint.plugin.ai.skill.service.publish;

import java.util.Objects;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillVersion;
import org.simplepoint.plugin.ai.skill.api.model.SkillPublishTaskStage;
import org.simplepoint.plugin.ai.skill.api.model.SkillVersionCreateRequest;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillVersionRepository;
import org.simplepoint.plugin.ai.skill.api.service.AiSkillService;
import org.simplepoint.plugin.ai.skill.service.artifact.GeneratedSkillOciArtifact;
import org.simplepoint.plugin.ai.skill.service.artifact.OciSkillArtifactPublisher;
import org.simplepoint.plugin.ai.skill.service.artifact.PushedSkillOciArtifact;
import org.simplepoint.plugin.ai.skill.service.artifact.SkillOciArtifactGenerator;
import org.simplepoint.plugin.ai.skill.service.publish.AiSkillPublishCoordinator.PublishWork;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Executes slow Registry and verification work outside database transactions. */
@Component
@ConditionalOnProperty(
    prefix = "simplepoint.ai.skill.publish",
    name = "enabled",
    havingValue = "true"
)
public class AiSkillPublishProcessor {

  private final AiSkillPublishCoordinator coordinator;

  private final SkillOciArtifactGenerator generator;

  private final OciSkillArtifactPublisher publisher;

  private final AiSkillVersionRepository versionRepository;

  private final AiSkillService skillService;

  /** Creates the publication processor. */
  public AiSkillPublishProcessor(
      final AiSkillPublishCoordinator coordinator,
      final SkillOciArtifactGenerator generator,
      final OciSkillArtifactPublisher publisher,
      final AiSkillVersionRepository versionRepository,
      final AiSkillService skillService
  ) {
    this.coordinator = coordinator;
    this.generator = generator;
    this.publisher = publisher;
    this.versionRepository = versionRepository;
    this.skillService = skillService;
  }

  /** Runs one claimed task through deterministic, reentrant checkpoints. */
  public void execute(final PublishWork work) {
    try {
      GeneratedSkillOciArtifact generated = generator.generate(
          work.manifest(),
          work.skillCode(),
          work.version(),
          work.draftRevision()
      );
      if (!coordinator.checkpoint(work, SkillPublishTaskStage.PUSHING)) {
        return;
      }
      PushedSkillOciArtifact pushed = publisher.push(
          work.skillCode(),
          work.version(),
          generated
      );
      if (!coordinator.pushed(work, pushed)) {
        return;
      }
      AiSkillVersion version = resolveVersion(work, pushed);
      if (!coordinator.versionCreated(work, version.getId())) {
        return;
      }
      skillService.publishManagedVersion(
          work.skillId(),
          work.scopeType(),
          work.tenantId(),
          version.getId(),
          work.activate()
      );
      coordinator.succeed(work);
    } catch (RuntimeException ex) {
      coordinator.fail(work, ex);
    }
  }

  private AiSkillVersion resolveVersion(
      final PublishWork work,
      final PushedSkillOciArtifact artifact
  ) {
    AiSkillVersion existing = versionRepository
        .findActiveByVersionAndSkillId(work.version(), work.skillId())
        .orElse(null);
    if (existing != null) {
      return assertMatches(existing, artifact);
    }
    try {
      return skillService.createManagedVersion(
          work.skillId(),
          work.scopeType(),
          work.tenantId(),
          new SkillVersionCreateRequest(
              work.version(),
              artifact.artifactReference(),
              artifact.artifactDigest(),
              work.manifest()
          )
      );
    } catch (IllegalArgumentException ex) {
      return versionRepository.findActiveByVersionAndSkillId(
          work.version(),
          work.skillId()
      ).map(version -> assertMatches(version, artifact)).orElseThrow(() -> ex);
    }
  }

  private AiSkillVersion assertMatches(
      final AiSkillVersion version,
      final PushedSkillOciArtifact artifact
  ) {
    if (!Objects.equals(version.getArtifactReference(),
        artifact.artifactReference())
        || !Objects.equals(version.getArtifactDigest(),
        artifact.artifactDigest())
        || !Objects.equals(version.getArtifactConfigDigest(),
        artifact.configDigest())
        || !Objects.equals(version.getArtifactContentDigest(),
        artifact.contentDigest())
        || !Objects.equals(version.getContentHash(), artifact.contentHash())) {
      throw new IllegalStateException(
          "Existing Skill version does not match the publication Artifact"
      );
    }
    return version;
  }
}
