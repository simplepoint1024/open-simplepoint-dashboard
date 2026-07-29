package org.simplepoint.plugin.ai.skill.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDefinition;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecution;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecutionStep;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillToolBinding;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillVersion;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStartRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStepStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillVersionStatus;
import org.simplepoint.plugin.ai.skill.api.properties.SkillExecutionProperties;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDefinitionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillExecutionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillExecutionStepRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillToolBindingRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillVersionRepository;
import org.simplepoint.plugin.ai.skill.service.support.SkillJsonSchemaValidator;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowTemplateResolver;

@ExtendWith(MockitoExtension.class)
class AiSkillExecutionServiceImplTest {

  @Mock
  private AiSkillDefinitionRepository skillRepository;

  @Mock
  private AiSkillVersionRepository versionRepository;

  @Mock
  private AiSkillToolBindingRepository bindingRepository;

  @Mock
  private AiSkillExecutionRepository executionRepository;

  @Mock
  private AiSkillExecutionStepRepository stepRepository;

  @Mock
  private AiScopeAccessPolicy scopeAccessPolicy;

  private AiSkillExecutionServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new AiSkillExecutionServiceImpl(
        skillRepository,
        versionRepository,
        bindingRepository,
        executionRepository,
        stepRepository,
        scopeAccessPolicy,
        new SkillJsonSchemaValidator(),
        new SkillWorkflowTemplateResolver(),
        new SkillExecutionProperties(),
        new ObjectMapper()
    );
  }

  @Test
  void submitsPinnedToolStepAsDurablePendingExecution() {
    AiSkillDefinition skill = skill();
    AiSkillVersion version = version();
    AiSkillToolBinding binding = binding();
    when(scopeAccessPolicy.currentManagementScope())
        .thenReturn(new ScopeAssignment(AiResourceScope.SYSTEM, null));
    when(skillRepository.findActiveByIdForUpdate("skill-a"))
        .thenReturn(Optional.of(skill));
    when(versionRepository.findActiveById("version-a"))
        .thenReturn(Optional.of(version));
    when(executionRepository.findActiveByIdempotency(
        eq("skill-a"),
        eq(AiResourceScope.SYSTEM),
        isNull(),
        anyString()
    )).thenReturn(Optional.empty());
    when(bindingRepository.findAllActiveBySkillVersionId("version-a"))
        .thenReturn(List.of(binding));
    when(executionRepository.save(any())).thenAnswer(invocation -> {
      AiSkillExecution execution = invocation.getArgument(0);
      execution.setId("execution-a");
      return execution;
    });
    when(stepRepository.save(any())).thenAnswer(invocation -> {
      AiSkillExecutionStep step = invocation.getArgument(0);
      step.setId("step-a");
      return step;
    });

    AiSkillExecution result = service.start(
        "skill-a",
        new SkillExecutionStartRequest(
            "request-a",
            Map.of("message", "hello")
        )
    );

    assertThat(result.getStatus()).isEqualTo(SkillExecutionStatus.PENDING);
    assertThat(result.getInput()).isEqualTo(Map.of("message", "hello"));
    assertThat(result.getSteps()).singleElement().satisfies(step -> {
      assertThat(step.getStatus())
          .isEqualTo(SkillExecutionStepStatus.PENDING);
      assertThat(step.getCapabilitySnapshotId()).isEqualTo("snapshot-a");
      assertThat(step.getToolName()).isEqualTo("echo");
    });
    ArgumentCaptor<AiSkillExecutionStep> savedStep =
        ArgumentCaptor.forClass(AiSkillExecutionStep.class);
    verify(stepRepository).save(savedStep.capture());
    assertThat(savedStep.getValue().getArgumentsTemplateJson())
        .isEqualTo("{\"message\":{\"$ref\":\"input.message\"}}");
  }

  private static AiSkillDefinition skill() {
    AiSkillDefinition skill = new AiSkillDefinition();
    skill.setId("skill-a");
    skill.setCode("echo-skill");
    skill.setName("Echo skill");
    skill.setScopeType(AiResourceScope.SYSTEM);
    skill.setStatus(SkillStatus.ACTIVE);
    skill.setEnabled(true);
    skill.setActiveVersionId("version-a");
    return skill;
  }

  private static AiSkillVersion version() {
    AiSkillVersion version = new AiSkillVersion();
    version.setId("version-a");
    version.setSkillId("skill-a");
    version.setScopeType(AiResourceScope.SYSTEM);
    version.setStatus(SkillVersionStatus.PUBLISHED);
    version.setInputSchemaJson("""
        {
          "type": "object",
          "required": ["message"],
          "additionalProperties": false,
          "properties": {"message": {"type": "string"}}
        }
        """);
    version.setOutputSchemaJson("{\"type\":\"object\"}");
    version.setWorkflowJson("""
        {
          "steps": [
            {
              "id": "echo-step",
              "type": "tool",
              "tool": "echo",
              "arguments": {
                "message": {"$ref": "input.message"}
              }
            }
          ]
        }
        """);
    return version;
  }

  private static AiSkillToolBinding binding() {
    AiSkillToolBinding binding = new AiSkillToolBinding();
    binding.setId("binding-a");
    binding.setSkillVersionId("version-a");
    binding.setMcpServerId("server-a");
    binding.setCapabilitySnapshotId("snapshot-a");
    binding.setToolName("echo");
    binding.setToolAlias("echo");
    binding.setInputSchemaHash("a".repeat(64));
    return binding;
  }
}
