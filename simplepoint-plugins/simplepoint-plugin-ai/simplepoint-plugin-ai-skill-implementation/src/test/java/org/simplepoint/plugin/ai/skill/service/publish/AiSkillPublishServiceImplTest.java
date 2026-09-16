package org.simplepoint.plugin.ai.skill.service.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDefinition;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDraft;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDraftRevision;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillPublishTask;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftPublishRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftValidationStatus;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDefinitionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDraftRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDraftRevisionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillPublishTaskRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillVersionRepository;

@ExtendWith(MockitoExtension.class)
class AiSkillPublishServiceImplTest {

  @Mock
  private AiSkillDefinitionRepository skillRepository;

  @Mock
  private AiSkillDraftRepository draftRepository;

  @Mock
  private AiSkillDraftRevisionRepository revisionRepository;

  @Mock
  private AiSkillPublishTaskRepository taskRepository;

  @Mock
  private AiSkillVersionRepository versionRepository;

  @Mock
  private AiScopeAccessPolicy scopeAccessPolicy;

  private ObjectMapper mapper;

  private AiSkillPublishServiceImpl service;

  @BeforeEach
  void setUp() {
    AuthorizationContext context = new AuthorizationContext();
    context.setUserId("publisher-user");
    RequestContextHolder.setContext(
        RequestContextHolder.AUTHORIZATION_CONTEXT_KEY,
        context
    );
    mapper = new ObjectMapper().configure(
        SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,
        true
    );
    service = new AiSkillPublishServiceImpl(
        skillRepository,
        draftRepository,
        revisionRepository,
        taskRepository,
        versionRepository,
        scopeAccessPolicy,
        mapper,
        new AiSkillPublishMetrics(new SimpleMeterRegistry())
    );
  }

  @AfterEach
  void clearAuthorizationContext() {
    RequestContextHolder.clearContext(
        RequestContextHolder.AUTHORIZATION_CONTEXT_KEY
    );
  }

  @Test
  void pinsExactRevisionAndDerivesTargetManifestVersion() throws Exception {
    final AiSkillDefinition skill = skill();
    AiSkillDraft draft = draft();
    AiSkillDraftRevision revision = revision(draft.getId());
    when(skillRepository.findActiveByIdForUpdate("skill-a"))
        .thenReturn(Optional.of(skill));
    when(taskRepository.findActiveByIdempotency(
        any(), any(), any(), any()
    )).thenReturn(Optional.empty());
    when(versionRepository.findActiveByVersionAndSkillId("2.0.0", "skill-a"))
        .thenReturn(Optional.empty());
    when(draftRepository.findActiveBySkillId("skill-a"))
        .thenReturn(Optional.of(draft));
    when(revisionRepository.findActiveByDraftIdAndRevision("draft-a", 4))
        .thenReturn(Optional.of(revision));
    when(taskRepository.save(any())).thenAnswer(invocation -> {
      AiSkillPublishTask task = invocation.getArgument(0);
      task.setId("task-a");
      return task;
    });

    AiSkillPublishTask result = service.start(
        "skill-a",
        new SkillDraftPublishRequest(4, "2.0.0", true, "request-a")
    );

    assertThat(result.getDraftRevision()).isEqualTo(4);
    assertThat(result.getDraftContentHash())
        .isEqualTo(revision.getContentHash());
    assertThat(result.getActivate()).isTrue();
    assertThat(result.getRequestedBy()).isEqualTo("publisher-user");
    assertThat(result.getCreatedBy()).isEqualTo("publisher-user");
    @SuppressWarnings("unchecked")
    Map<String, Object> manifest = mapper.readValue(
        result.getManifestJson(),
        Map.class
    );
    @SuppressWarnings("unchecked")
    Map<String, Object> metadata =
        (Map<String, Object>) manifest.get("metadata");
    assertThat(metadata)
        .containsEntry("name", "document-summary")
        .containsEntry("version", "2.0.0");
    verify(scopeAccessPolicy).assertCanManageOwnedResource(
        AiResourceScope.SYSTEM,
        null
    );
  }

  @Test
  void rejectsIdempotencyKeyReuseWithDifferentRequest() {
    final AiSkillDefinition skill = skill();
    AiSkillPublishTask existing = new AiSkillPublishTask();
    existing.setDraftRevision(4L);
    existing.setVersion("2.0.0");
    existing.setActivate(true);
    when(skillRepository.findActiveByIdForUpdate("skill-a"))
        .thenReturn(Optional.of(skill));
    when(taskRepository.findActiveByIdempotency(
        any(), any(), any(), any()
    )).thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> service.start(
        "skill-a",
        new SkillDraftPublishRequest(5, "2.0.0", true, "request-a")
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("different publication");
  }

  private AiSkillDefinition skill() {
    AiSkillDefinition skill = new AiSkillDefinition();
    skill.setId("skill-a");
    skill.setCode("document-summary");
    skill.setScopeType(AiResourceScope.SYSTEM);
    skill.setEnabled(true);
    return skill;
  }

  private AiSkillDraft draft() {
    AiSkillDraft draft = new AiSkillDraft();
    draft.setId("draft-a");
    draft.setSkillId("skill-a");
    return draft;
  }

  private AiSkillDraftRevision revision(final String draftId) throws Exception {
    Map<String, Object> manifest = Map.of(
        "apiVersion", "simplepoint.io/v1alpha1",
        "kind", "Skill",
        "metadata", Map.of(
            "name", "document-summary",
            "version", "1.0.0"
        ),
        "spec", Map.of()
    );
    String json = mapper.writeValueAsString(manifest);
    AiSkillDraftRevision revision = new AiSkillDraftRevision();
    revision.setDraftId(draftId);
    revision.setRevision(4L);
    revision.setValidationStatus(SkillDraftValidationStatus.VALID);
    revision.setCompiledManifestJson(json);
    revision.setContentHash(HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(
            json.getBytes(StandardCharsets.UTF_8)
        )
    ));
    return revision;
  }
}
