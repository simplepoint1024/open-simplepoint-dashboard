package org.simplepoint.plugin.ai.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentDefinition;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentSkillBinding;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentVersion;
import org.simplepoint.plugin.ai.agent.api.model.AgentStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentUpsertRequest;
import org.simplepoint.plugin.ai.agent.api.model.AgentVersionCreateRequest;
import org.simplepoint.plugin.ai.agent.api.model.AgentVersionStatus;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentDefinitionRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentSkillBindingRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentVersionRepository;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;
import org.simplepoint.plugin.ai.core.api.model.AiModelType;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.api.repository.AiModelDefinitionRepository;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDefinition;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillVersion;
import org.simplepoint.plugin.ai.skill.api.model.SkillStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillVersionStatus;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDefinitionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillVersionRepository;
import org.simplepoint.plugin.ai.skill.service.support.SkillJsonSchemaValidator;

class AiAgentServiceImplTest {

  private AiAgentDefinitionRepository agentRepository;

  private AiAgentVersionRepository versionRepository;

  private AiAgentSkillBindingRepository bindingRepository;

  private AiModelDefinitionRepository modelRepository;

  private AiSkillDefinitionRepository skillRepository;

  private AiSkillVersionRepository skillVersionRepository;

  private AiScopeAccessPolicy scopeAccessPolicy;

  private AiAgentServiceImpl service;

  @BeforeEach
  void setUp() {
    agentRepository = mock(AiAgentDefinitionRepository.class);
    versionRepository = mock(AiAgentVersionRepository.class);
    bindingRepository = mock(AiAgentSkillBindingRepository.class);
    modelRepository = mock(AiModelDefinitionRepository.class);
    skillRepository = mock(AiSkillDefinitionRepository.class);
    skillVersionRepository = mock(AiSkillVersionRepository.class);
    scopeAccessPolicy = mock(AiScopeAccessPolicy.class);
    service = new AiAgentServiceImpl(
        agentRepository,
        versionRepository,
        bindingRepository,
        modelRepository,
        skillRepository,
        skillVersionRepository,
        scopeAccessPolicy,
        new SkillJsonSchemaValidator(),
        new ObjectMapper()
    );
  }

  @Test
  void createsImmutableVersionAndPinsPublishedSkill() {
    AiAgentDefinition agent = agent("agent-1");
    AiModelDefinition model = model("model-1", AiResourceScope.SYSTEM, null);
    AiSkillDefinition skill = skill("skill-1");
    AiSkillVersion skillVersion = skillVersion("skill-version-1");
    when(agentRepository.findActiveByIdForUpdate(agent.getId()))
        .thenReturn(Optional.of(agent));
    when(versionRepository.findActiveByVersionAndAgentId(
        "1.0.0",
        agent.getId()
    )).thenReturn(Optional.empty());
    when(modelRepository.findActiveById(model.getId()))
        .thenReturn(Optional.of(model));
    when(skillRepository.findActiveById(skill.getId()))
        .thenReturn(Optional.of(skill));
    when(skillVersionRepository.findActiveByIdAndSkillId(
        skillVersion.getId(),
        skill.getId()
    )).thenReturn(Optional.of(skillVersion));
    when(scopeAccessPolicy.canUseResourceFromScope(
        any(),
        any(),
        any(),
        any()
    )).thenReturn(true);
    when(versionRepository.save(any(AiAgentVersion.class)))
        .thenAnswer(invocation -> {
          AiAgentVersion value = invocation.getArgument(0);
          value.setId("agent-version-1");
          return value;
        });
    when(bindingRepository.save(any(AiAgentSkillBinding.class)))
        .thenAnswer(invocation -> {
          AiAgentSkillBinding value = invocation.getArgument(0);
          value.setId("binding-1");
          return value;
        });

    AiAgentVersion created = service.createVersion(
        agent.getId(),
        new AgentVersionCreateRequest(
            "1.0.0",
            manifest(agent.getCode(), model.getId(), skill, skillVersion)
        )
    );

    assertThat(created.getStatus()).isEqualTo(AgentVersionStatus.DRAFT);
    assertThat(created.getContentHash()).matches("[0-9a-f]{64}");
    assertThat(created.getPrimaryModelId()).isEqualTo(model.getId());
    assertThat(created.getSkillBindings()).singleElement()
        .satisfies(binding -> {
          assertThat(binding.getSkillAlias()).isEqualTo("summarize");
          assertThat(binding.getSkillVersionId())
              .isEqualTo(skillVersion.getId());
          assertThat(binding.getSkillContentHash())
              .isEqualTo(skillVersion.getContentHash());
        });
  }

  @Test
  void rejectsModelOutsideAgentScope() {
    AiAgentDefinition agent = agent("agent-1");
    AiModelDefinition model =
        model("foreign-model", AiResourceScope.TENANT, "tenant-b");
    when(agentRepository.findActiveByIdForUpdate(agent.getId()))
        .thenReturn(Optional.of(agent));
    when(modelRepository.findActiveById(model.getId()))
        .thenReturn(Optional.of(model));
    when(scopeAccessPolicy.canUseResourceFromScope(
        any(),
        any(),
        any(),
        any()
    )).thenReturn(false);

    assertThatThrownBy(() -> service.createVersion(
        agent.getId(),
        new AgentVersionCreateRequest(
            "1.0.0",
            manifest(agent.getCode(), model.getId(), null, null)
        )
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("outside the Agent scope");
  }

  @Test
  void publishRevalidatesPinnedDependenciesAndActivatesVersion() {
    AiAgentDefinition agent = agent("agent-1");
    AiModelDefinition model = model("model-1", AiResourceScope.SYSTEM, null);
    AiSkillDefinition skill = skill("skill-1");
    AiSkillVersion skillVersion = skillVersion("skill-version-1");
    AiAgentVersion version = new AiAgentVersion();
    version.setId("agent-version-1");
    version.setAgentId(agent.getId());
    version.setScopeType(agent.getScopeType());
    version.setVersion("1.0.0");
    version.setStatus(AgentVersionStatus.DRAFT);
    version.setManifestJson(writeManifest(
        manifest(agent.getCode(), model.getId(), skill, skillVersion)
    ));
    AiAgentSkillBinding binding = new AiAgentSkillBinding();
    binding.setSkillId(skill.getId());
    binding.setSkillVersionId(skillVersion.getId());
    binding.setSkillContentHash(skillVersion.getContentHash());

    when(agentRepository.findActiveByIdForUpdate(agent.getId()))
        .thenReturn(Optional.of(agent));
    when(versionRepository.findActiveByIdAndAgentIdForUpdate(
        version.getId(),
        agent.getId()
    )).thenReturn(Optional.of(version));
    when(modelRepository.findActiveById(model.getId()))
        .thenReturn(Optional.of(model));
    when(bindingRepository.findAllActiveByAgentVersionId(version.getId()))
        .thenReturn(List.of(binding));
    when(skillRepository.findActiveById(skill.getId()))
        .thenReturn(Optional.of(skill));
    when(skillVersionRepository.findActiveByIdAndSkillId(
        skillVersion.getId(),
        skill.getId()
    )).thenReturn(Optional.of(skillVersion));
    when(scopeAccessPolicy.canUseResourceFromScope(
        any(),
        any(),
        any(),
        any()
    )).thenReturn(true);
    when(versionRepository.save(any(AiAgentVersion.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(agentRepository.save(any(AiAgentDefinition.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    AiAgentVersion published = service.publishVersion(
        agent.getId(),
        version.getId()
    );

    assertThat(published.getStatus()).isEqualTo(AgentVersionStatus.PUBLISHED);
    assertThat(agent.getStatus()).isEqualTo(AgentStatus.ACTIVE);
    assertThat(agent.getActiveVersionId()).isEqualTo(version.getId());
    verify(versionRepository).save(version);
    verify(agentRepository).save(agent);
  }

  @Test
  void definitionCodeIsImmutable() {
    AiAgentDefinition agent = agent("agent-1");
    when(agentRepository.findActiveByIdForUpdate(agent.getId()))
        .thenReturn(Optional.of(agent));

    assertThatThrownBy(() -> service.update(
        agent.getId(),
        new AgentUpsertRequest("changed", "Agent", null, true)
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("immutable");
  }

  private static AiAgentDefinition agent(final String id) {
    AiAgentDefinition agent = new AiAgentDefinition();
    agent.setId(id);
    agent.setScopeType(AiResourceScope.SYSTEM);
    agent.setCode("document-agent");
    agent.setName("Document Agent");
    agent.setEnabled(true);
    agent.setStatus(AgentStatus.DRAFT);
    return agent;
  }

  private static AiModelDefinition model(
      final String id,
      final AiResourceScope scope,
      final String tenantId
  ) {
    AiModelDefinition model = new AiModelDefinition();
    model.setId(id);
    model.setScopeType(scope);
    model.setTenantId(tenantId);
    model.setModelType(AiModelType.LLM);
    model.setEnabled(true);
    model.setAvailable(true);
    return model;
  }

  private static AiSkillDefinition skill(final String id) {
    AiSkillDefinition skill = new AiSkillDefinition();
    skill.setId(id);
    skill.setScopeType(AiResourceScope.SYSTEM);
    skill.setCode("summary-skill");
    skill.setName("Summary Skill");
    skill.setEnabled(true);
    skill.setStatus(SkillStatus.ACTIVE);
    return skill;
  }

  private static AiSkillVersion skillVersion(final String id) {
    AiSkillVersion version = new AiSkillVersion();
    version.setId(id);
    version.setScopeType(AiResourceScope.SYSTEM);
    version.setVersion("1.3.0");
    version.setContentHash(
        "8f7f353acee012ddb8afaa44a75a2eb07df35e82799b8ed92248098df35e132b"
    );
    version.setStatus(SkillVersionStatus.PUBLISHED);
    return version;
  }

  private static Map<String, Object> manifest(
      final String code,
      final String modelId,
      final AiSkillDefinition skill,
      final AiSkillVersion skillVersion
  ) {
    List<Map<String, Object>> skills = skill == null
        ? List.of()
        : List.of(Map.of(
            "alias", "summarize",
            "skillId", skill.getId(),
            "versionId", skillVersion.getId()
        ));
    return Map.of(
        "apiVersion", "simplepoint.io/v1alpha1",
        "kind", "Agent",
        "metadata", Map.of("name", code, "version", "1.0.0"),
        "spec", Map.ofEntries(
            Map.entry("systemPrompt", "You are a document assistant."),
            Map.entry("model", Map.of(
                "primaryModelId", modelId,
                "fallbackModelIds", List.of()
            )),
            Map.entry("skills", skills),
            Map.entry("behavior", Map.of(
                "instructions", List.of(
                    "Never bypass the bound Skill."
                ),
                "responseStyle", "concise"
            )),
            Map.entry("memory", Map.of(
                "shortTermEnabled", true,
                "longTermEnabled", false,
                "maximumMessages", 20
            )),
            Map.entry("budgets", Map.of(
                "maximumSteps", 16,
                "maximumLoopDepth", 4,
                "maximumConcurrency", 4,
                "maximumInputTokens", 32000,
                "maximumOutputTokens", 4096,
                "maximumCost", 10
            )),
            Map.entry("approvals", Map.of(
                "execution", Map.of(
                    "required", false,
                    "allowSelfApproval", false
                )
            )),
            Map.entry("inputSchema", Map.of(
                "type", "object",
                "properties", Map.of(),
                "additionalProperties", false
            )),
            Map.entry("outputSchema", Map.of(
                "type", "object",
                "properties", Map.of(),
                "additionalProperties", false
            )),
            Map.entry("publicAccess", false)
        )
    );
  }

  private static String writeManifest(final Map<String, Object> manifest) {
    try {
      return new ObjectMapper().writeValueAsString(manifest);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }
}
