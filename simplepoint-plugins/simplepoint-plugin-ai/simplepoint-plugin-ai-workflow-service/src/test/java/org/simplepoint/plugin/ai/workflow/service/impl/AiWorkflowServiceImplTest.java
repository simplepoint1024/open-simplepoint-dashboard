package org.simplepoint.plugin.ai.workflow.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentVersion;
import org.simplepoint.plugin.ai.agent.api.model.AgentStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentVersionStatus;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentDefinitionRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentVersionRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDefinition;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillVersion;
import org.simplepoint.plugin.ai.skill.api.model.SkillStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillVersionStatus;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDefinitionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillVersionRepository;
import org.simplepoint.plugin.ai.skill.service.support.SkillJsonSchemaValidator;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowDefinition;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowDependencyBinding;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowVersion;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowStatus;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowVersionCreateRequest;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowVersionStatus;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowDefinitionRepository;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowDependencyBindingRepository;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowVersionRepository;
import org.simplepoint.plugin.ai.workflow.service.support.WorkflowManifestCompiler;

class AiWorkflowServiceImplTest {

  private AiWorkflowDefinitionRepository workflowRepository;

  private AiWorkflowVersionRepository versionRepository;

  private AiWorkflowDependencyBindingRepository bindingRepository;

  private AiAgentDefinitionRepository agentRepository;

  private AiAgentVersionRepository agentVersionRepository;

  private AiSkillDefinitionRepository skillRepository;

  private AiSkillVersionRepository skillVersionRepository;

  private AiScopeAccessPolicy scopeAccessPolicy;

  private AiWorkflowServiceImpl service;

  @BeforeEach
  void setUp() {
    workflowRepository = mock(AiWorkflowDefinitionRepository.class);
    versionRepository = mock(AiWorkflowVersionRepository.class);
    bindingRepository =
        mock(AiWorkflowDependencyBindingRepository.class);
    agentRepository = mock(AiAgentDefinitionRepository.class);
    agentVersionRepository = mock(AiAgentVersionRepository.class);
    skillRepository = mock(AiSkillDefinitionRepository.class);
    skillVersionRepository = mock(AiSkillVersionRepository.class);
    scopeAccessPolicy = mock(AiScopeAccessPolicy.class);
    service = new AiWorkflowServiceImpl(
        workflowRepository,
        versionRepository,
        bindingRepository,
        agentRepository,
        agentVersionRepository,
        skillRepository,
        skillVersionRepository,
        scopeAccessPolicy,
        new SkillJsonSchemaValidator(),
        new WorkflowManifestCompiler(),
        new ObjectMapper()
    );
  }

  @Test
  void createsImmutableVersionAndPinsAgentAndSkill() {
    AiWorkflowDefinition workflow = workflow();
    AiAgentDefinition agent = agent();
    AiAgentVersion agentVersion = agentVersion();
    AiSkillDefinition skill = skill();
    AiSkillVersion skillVersion = skillVersion();
    stubDependencies(workflow, agent, agentVersion, skill, skillVersion);
    when(versionRepository.findActiveByVersionAndWorkflowId(
        "1.0.0",
        workflow.getId()
    )).thenReturn(Optional.empty());
    when(versionRepository.save(any(AiWorkflowVersion.class)))
        .thenAnswer(invocation -> {
          AiWorkflowVersion value = invocation.getArgument(0);
          value.setId("workflow-version-1");
          return value;
        });
    when(bindingRepository.save(any(AiWorkflowDependencyBinding.class)))
        .thenAnswer(invocation -> {
          AiWorkflowDependencyBinding value = invocation.getArgument(0);
          value.setId("binding-" + value.getBindingOrder());
          return value;
        });

    AiWorkflowVersion created = service.createVersion(
        workflow.getId(),
        new WorkflowVersionCreateRequest(
            "1.0.0",
            manifest(workflow, agent, agentVersion, skill, skillVersion)
        )
    );

    assertThat(created.getStatus()).isEqualTo(WorkflowVersionStatus.DRAFT);
    assertThat(created.getContentHash()).matches("[0-9a-f]{64}");
    assertThat(created.getDependencies()).hasSize(2);
    assertThat(created.getDependencies())
        .extracting(AiWorkflowDependencyBinding::getResourceVersionId)
        .containsExactly(agentVersion.getId(), skillVersion.getId());
  }

  @Test
  void publishRevalidatesHashesAndActivatesVersion() {
    AiWorkflowDefinition workflow = workflow();
    AiAgentDefinition agent = agent();
    AiAgentVersion agentVersion = agentVersion();
    AiSkillDefinition skill = skill();
    AiSkillVersion skillVersion = skillVersion();
    stubDependencies(workflow, agent, agentVersion, skill, skillVersion);
    AiWorkflowVersion version = new AiWorkflowVersion();
    version.setId("workflow-version-1");
    version.setWorkflowId(workflow.getId());
    version.setStatus(WorkflowVersionStatus.DRAFT);
    version.setManifestJson("{}");
    AiWorkflowDependencyBinding agentBinding = binding(
        "agent-node",
        org.simplepoint.plugin.ai.workflow.api.model.WorkflowDependencyType.AGENT,
        agent.getId(),
        agentVersion.getId(),
        agentVersion.getContentHash()
    );
    AiWorkflowDependencyBinding skillBinding = binding(
        "skill-node",
        org.simplepoint.plugin.ai.workflow.api.model.WorkflowDependencyType.SKILL,
        skill.getId(),
        skillVersion.getId(),
        skillVersion.getContentHash()
    );
    when(versionRepository.findActiveByIdAndWorkflowIdForUpdate(
        version.getId(),
        workflow.getId()
    )).thenReturn(Optional.of(version));
    when(bindingRepository.findAllActiveByWorkflowVersionId(version.getId()))
        .thenReturn(List.of(agentBinding, skillBinding));
    when(versionRepository.save(any(AiWorkflowVersion.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(workflowRepository.save(any(AiWorkflowDefinition.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    AiWorkflowVersion published = service.publishVersion(
        workflow.getId(),
        version.getId()
    );

    assertThat(published.getStatus())
        .isEqualTo(WorkflowVersionStatus.PUBLISHED);
    assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.ACTIVE);
    assertThat(workflow.getActiveVersionId()).isEqualTo(version.getId());
    verify(workflowRepository).save(workflow);
  }

  private void stubDependencies(
      final AiWorkflowDefinition workflow,
      final AiAgentDefinition agent,
      final AiAgentVersion agentVersion,
      final AiSkillDefinition skill,
      final AiSkillVersion skillVersion
  ) {
    when(workflowRepository.findActiveByIdForUpdate(workflow.getId()))
        .thenReturn(Optional.of(workflow));
    when(agentRepository.findActiveById(agent.getId()))
        .thenReturn(Optional.of(agent));
    when(agentVersionRepository.findActiveByIdAndAgentId(
        agentVersion.getId(),
        agent.getId()
    )).thenReturn(Optional.of(agentVersion));
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
  }

  private static AiWorkflowDefinition workflow() {
    AiWorkflowDefinition value = new AiWorkflowDefinition();
    value.setId("workflow-1");
    value.setScopeType(AiResourceScope.SYSTEM);
    value.setCode("document-flow");
    value.setName("Document Flow");
    value.setEnabled(true);
    value.setStatus(WorkflowStatus.DRAFT);
    return value;
  }

  private static AiAgentDefinition agent() {
    AiAgentDefinition value = new AiAgentDefinition();
    value.setId("agent-1");
    value.setScopeType(AiResourceScope.SYSTEM);
    value.setCode("document-agent");
    value.setEnabled(true);
    value.setStatus(AgentStatus.ACTIVE);
    return value;
  }

  private static AiAgentVersion agentVersion() {
    AiAgentVersion value = new AiAgentVersion();
    value.setId("agent-version-1");
    value.setAgentId("agent-1");
    value.setScopeType(AiResourceScope.SYSTEM);
    value.setVersion("2.0.0");
    value.setContentHash(
        "1111111111111111111111111111111111111111111111111111111111111111"
    );
    value.setStatus(AgentVersionStatus.PUBLISHED);
    return value;
  }

  private static AiSkillDefinition skill() {
    AiSkillDefinition value = new AiSkillDefinition();
    value.setId("skill-1");
    value.setScopeType(AiResourceScope.SYSTEM);
    value.setCode("publish-skill");
    value.setEnabled(true);
    value.setStatus(SkillStatus.ACTIVE);
    return value;
  }

  private static AiSkillVersion skillVersion() {
    AiSkillVersion value = new AiSkillVersion();
    value.setId("skill-version-1");
    value.setSkillId("skill-1");
    value.setScopeType(AiResourceScope.SYSTEM);
    value.setVersion("3.0.0");
    value.setContentHash(
        "2222222222222222222222222222222222222222222222222222222222222222"
    );
    value.setStatus(SkillVersionStatus.PUBLISHED);
    return value;
  }

  private static AiWorkflowDependencyBinding binding(
      final String nodeId,
      final org.simplepoint.plugin.ai.workflow.api.model.WorkflowDependencyType type,
      final String resourceId,
      final String versionId,
      final String hash
  ) {
    AiWorkflowDependencyBinding value =
        new AiWorkflowDependencyBinding();
    value.setNodeId(nodeId);
    value.setDependencyType(type);
    value.setResourceId(resourceId);
    value.setResourceVersionId(versionId);
    value.setResourceContentHash(hash);
    return value;
  }

  private static Map<String, Object> manifest(
      final AiWorkflowDefinition workflow,
      final AiAgentDefinition agent,
      final AiAgentVersion agentVersion,
      final AiSkillDefinition skill,
      final AiSkillVersion skillVersion
  ) {
    return Map.of(
        "apiVersion",
        "simplepoint.io/v1alpha1",
        "kind",
        "AgentWorkflow",
        "metadata",
        Map.of("name", workflow.getCode(), "version", "1.0.0"),
        "spec",
        Map.of(
            "inputSchema",
            Map.of("type", "object"),
            "outputSchema",
            Map.of("type", "object"),
            "budgets",
            Map.of(
                "maximumDurationSeconds",
                3600,
                "maximumNodeExecutions",
                16,
                "maximumParallelism",
                2
            ),
            "nodes",
            List.of(
                Map.of(
                    "id",
                    "agent-node",
                    "type",
                    "agent",
                    "agentId",
                    agent.getId(),
                    "versionId",
                    agentVersion.getId()
                ),
                Map.of(
                    "id",
                    "skill-node",
                    "type",
                    "skill",
                    "skillId",
                    skill.getId(),
                    "versionId",
                    skillVersion.getId()
                ),
                Map.of("id", "done", "type", "end")
            ),
            "edges",
            List.of(
                Map.of("from", "agent-node", "to", "skill-node"),
                Map.of("from", "skill-node", "to", "done")
            )
        )
    );
  }
}
