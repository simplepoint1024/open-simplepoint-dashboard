package org.simplepoint.plugin.ai.skill.service.publish;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillVersion;
import org.simplepoint.plugin.ai.skill.api.model.SkillVersionCreateRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillVersionStatus;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillVersionRepository;
import org.simplepoint.plugin.ai.skill.api.service.AiSkillService;
import org.simplepoint.plugin.ai.skill.service.artifact.GeneratedSkillOciArtifact;
import org.simplepoint.plugin.ai.skill.service.artifact.OciSkillArtifactPublisher;
import org.simplepoint.plugin.ai.skill.service.artifact.PushedSkillOciArtifact;
import org.simplepoint.plugin.ai.skill.service.artifact.SkillOciArtifactGenerator;
import org.simplepoint.plugin.ai.skill.service.publish.AiSkillPublishCoordinator.PublishWork;

@ExtendWith(MockitoExtension.class)
class AiSkillPublishProcessorTest {

  @Mock
  private AiSkillPublishCoordinator coordinator;

  @Mock
  private SkillOciArtifactGenerator generator;

  @Mock
  private OciSkillArtifactPublisher publisher;

  @Mock
  private AiSkillVersionRepository versionRepository;

  @Mock
  private AiSkillService skillService;

  @Test
  void createsAndActivatesVerifiedVersionBeforeCompletingTask() {
    PublishWork work = work(true);
    GeneratedSkillOciArtifact generated = generated();
    PushedSkillOciArtifact pushed = pushed();
    AiSkillVersion version = version(SkillVersionStatus.DRAFT);
    when(generator.generate(work.manifest(), "document-summary", "2.0.0", 4))
        .thenReturn(generated);
    when(coordinator.checkpoint(any(), any())).thenReturn(true);
    when(publisher.push("document-summary", "2.0.0", generated))
        .thenReturn(pushed);
    when(coordinator.pushed(work, pushed)).thenReturn(true);
    when(versionRepository.findActiveByVersionAndSkillId("2.0.0", "skill-a"))
        .thenReturn(Optional.empty());
    when(skillService.createManagedVersion(
        any(), any(), any(), any(SkillVersionCreateRequest.class)
    )).thenReturn(version);
    when(coordinator.versionCreated(work, "version-a")).thenReturn(true);

    processor().execute(work);

    verify(skillService).publishManagedVersion(
        "skill-a",
        AiResourceScope.SYSTEM,
        null,
        "version-a",
        true
    );
    verify(coordinator).succeed(work);
    verify(coordinator, never()).fail(any(), any());
  }

  @Test
  void reusesMatchingVersionAfterRestartWithoutCreatingDuplicate() {
    PublishWork work = work(false);
    GeneratedSkillOciArtifact generated = generated();
    PushedSkillOciArtifact pushed = pushed();
    AiSkillVersion version = version(SkillVersionStatus.PUBLISHED);
    when(generator.generate(work.manifest(), "document-summary", "2.0.0", 4))
        .thenReturn(generated);
    when(coordinator.checkpoint(any(), any())).thenReturn(true);
    when(publisher.push("document-summary", "2.0.0", generated))
        .thenReturn(pushed);
    when(coordinator.pushed(work, pushed)).thenReturn(true);
    when(versionRepository.findActiveByVersionAndSkillId("2.0.0", "skill-a"))
        .thenReturn(Optional.of(version));
    when(coordinator.versionCreated(work, "version-a")).thenReturn(true);

    processor().execute(work);

    verify(skillService, never()).createManagedVersion(
        any(), any(), any(), any()
    );
    verify(skillService).publishManagedVersion(
        "skill-a",
        AiResourceScope.SYSTEM,
        null,
        "version-a",
        false
    );
    verify(coordinator).succeed(work);
  }

  @Test
  void reassertsActivationAfterRestartEvenWhenVersionIsPublished() {
    PublishWork work = work(true);
    GeneratedSkillOciArtifact generated = generated();
    PushedSkillOciArtifact pushed = pushed();
    AiSkillVersion version = version(SkillVersionStatus.PUBLISHED);
    when(generator.generate(work.manifest(), "document-summary", "2.0.0", 4))
        .thenReturn(generated);
    when(coordinator.checkpoint(any(), any())).thenReturn(true);
    when(publisher.push("document-summary", "2.0.0", generated))
        .thenReturn(pushed);
    when(coordinator.pushed(work, pushed)).thenReturn(true);
    when(versionRepository.findActiveByVersionAndSkillId("2.0.0", "skill-a"))
        .thenReturn(Optional.of(version));
    when(coordinator.versionCreated(work, "version-a")).thenReturn(true);

    processor().execute(work);

    verify(skillService).publishManagedVersion(
        "skill-a",
        AiResourceScope.SYSTEM,
        null,
        "version-a",
        true
    );
    verify(coordinator).succeed(work);
  }

  private AiSkillPublishProcessor processor() {
    return new AiSkillPublishProcessor(
        coordinator,
        generator,
        publisher,
        versionRepository,
        skillService
    );
  }

  private PublishWork work(final boolean activate) {
    return new PublishWork(
        "task-a",
        1L,
        "worker-a",
        "skill-a",
        AiResourceScope.SYSTEM,
        null,
        "document-summary",
        4L,
        "2.0.0",
        activate,
        Map.of("metadata", Map.of(
            "name", "document-summary",
            "version", "2.0.0"
        ))
    );
  }

  private GeneratedSkillOciArtifact generated() {
    return new GeneratedSkillOciArtifact(
        new byte[]{1},
        "sha256:" + "a".repeat(64),
        new byte[]{2},
        "sha256:" + "b".repeat(64),
        "b".repeat(64),
        new byte[]{3},
        "sha256:" + "c".repeat(64)
    );
  }

  private PushedSkillOciArtifact pushed() {
    return new PushedSkillOciArtifact(
        "registry.example/simplepoint/skills/document-summary:2.0.0",
        "sha256:" + "c".repeat(64),
        "sha256:" + "a".repeat(64),
        "sha256:" + "b".repeat(64),
        "b".repeat(64)
    );
  }

  private AiSkillVersion version(final SkillVersionStatus status) {
    PushedSkillOciArtifact pushed = pushed();
    AiSkillVersion version = new AiSkillVersion();
    version.setId("version-a");
    version.setStatus(status);
    version.setArtifactReference(pushed.artifactReference());
    version.setArtifactDigest(pushed.artifactDigest());
    version.setArtifactConfigDigest(pushed.configDigest());
    version.setArtifactContentDigest(pushed.contentDigest());
    version.setContentHash(pushed.contentHash());
    return version;
  }
}
