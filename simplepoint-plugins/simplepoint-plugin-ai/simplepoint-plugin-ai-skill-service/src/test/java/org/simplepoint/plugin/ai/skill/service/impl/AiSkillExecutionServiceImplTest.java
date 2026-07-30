package org.simplepoint.plugin.ai.skill.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDefinition;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecution;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecutionStep;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillToolBinding;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillVersion;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionDecisionRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionPauseRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStartRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStepStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillVersionStatus;
import org.simplepoint.plugin.ai.skill.api.properties.SkillExecutionProperties;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDefinitionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillExecutionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillExecutionStepRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillPromptBindingRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillResourceBindingRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillToolBindingRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillVersionRepository;
import org.simplepoint.plugin.ai.skill.service.support.SkillApprovalPolicy;
import org.simplepoint.plugin.ai.skill.service.support.SkillBudgetPolicy;
import org.simplepoint.plugin.ai.skill.service.support.SkillJsonSchemaValidator;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowConditionEvaluator;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler;
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
  private AiSkillPromptBindingRepository promptBindingRepository;

  @Mock
  private AiSkillResourceBindingRepository resourceBindingRepository;

  @Mock
  private AiSkillExecutionRepository executionRepository;

  @Mock
  private AiSkillExecutionStepRepository stepRepository;

  @Mock
  private AiScopeAccessPolicy scopeAccessPolicy;

  private AiSkillExecutionServiceImpl service;

  @BeforeEach
  void setUp() {
    ObjectMapper objectMapper = new ObjectMapper();
    SkillExecutionProperties properties = new SkillExecutionProperties();
    SkillWorkflowTemplateResolver templateResolver =
        new SkillWorkflowTemplateResolver();
    SkillWorkflowConditionEvaluator conditionEvaluator =
        new SkillWorkflowConditionEvaluator(templateResolver);
    service = new AiSkillExecutionServiceImpl(
        skillRepository,
        versionRepository,
        bindingRepository,
        promptBindingRepository,
        resourceBindingRepository,
        executionRepository,
        stepRepository,
        scopeAccessPolicy,
        new SkillJsonSchemaValidator(),
        new SkillWorkflowPlanCompiler(
            templateResolver,
            conditionEvaluator
        ),
        new SkillBudgetPolicy(properties, objectMapper),
        new SkillApprovalPolicy(objectMapper),
        properties,
        objectMapper
    );
  }

  @Test
  void submitsPinnedToolStepAsDurablePendingExecution() {
    final AiSkillDefinition skill = skill();
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
    assertThat(result.getMaximumToolCalls()).isEqualTo(1);
    assertThat(result.getMaximumDurationSeconds()).isEqualTo(300);
    assertThat(result.getMaximumPayloadBytes()).isEqualTo(1024L * 1024L);
    assertThat(result.getConsumedToolCalls()).isZero();
    assertThat(result.getConsumedPayloadBytes()).isPositive();
    assertThat(result.getDeadlineAt()).isNotNull();
    assertThat(result.getSteps()).singleElement().satisfies(step -> {
      assertThat(step.getStatus())
          .isEqualTo(SkillExecutionStepStatus.PENDING);
      assertThat(step.getCapabilitySnapshotId()).isEqualTo("snapshot-a");
      assertThat(step.getCapabilityName()).isEqualTo("echo");
    });
    ArgumentCaptor<AiSkillExecutionStep> savedStep =
        ArgumentCaptor.forClass(AiSkillExecutionStep.class);
    verify(stepRepository).save(savedStep.capture());
    assertThat(savedStep.getValue().getInputTemplateJson())
        .isEqualTo("{\"message\":{\"$ref\":\"input.message\"}}");
  }

  @Test
  void snapshotsControlPlanAndFlattensAllDurableToolLeaves() {
    final AiSkillDefinition skill = skill();
    AiSkillVersion version = version();
    version.setInputSchemaJson("{\"type\":\"object\"}");
    version.setWorkflowJson("""
        {
          "steps": [
            {
              "id": "route",
              "type": "condition",
              "condition": {
                "equals": [
                  {"$ref": "input.mode"},
                  "full"
                ]
              },
              "then": [
                {"id": "full", "type": "tool", "tool": "echo"}
              ],
              "else": [
                {"id": "compact", "type": "tool", "tool": "echo"}
              ]
            },
            {
              "id": "fanout",
              "type": "parallel",
              "branches": [
                {
                  "id": "left",
                  "steps": [
                    {"id": "left-call", "type": "tool", "tool": "echo"}
                  ]
                },
                {
                  "id": "right",
                  "steps": [
                    {"id": "right-call", "type": "tool", "tool": "echo"}
                  ]
                }
              ]
            }
          ]
        }
        """);
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
        .thenReturn(List.of(binding()));
    when(executionRepository.save(any())).thenAnswer(invocation -> {
      AiSkillExecution execution = invocation.getArgument(0);
      execution.setId("execution-control");
      return execution;
    });
    when(stepRepository.save(any())).thenAnswer(invocation -> {
      AiSkillExecutionStep step = invocation.getArgument(0);
      step.setId("row-" + step.getStepId());
      return step;
    });

    AiSkillExecution result = service.start(
        "skill-a",
        new SkillExecutionStartRequest(
            "request-control",
            Map.of("mode", "full")
        )
    );

    assertThat(result.getWorkflowPlanJson())
        .contains("\"type\":\"condition\"")
        .contains("\"type\":\"parallel\"");
    assertThat(result.getMaximumToolCalls()).isEqualTo(3);
    assertThat(result.getSteps())
        .extracting(AiSkillExecutionStep::getStepId)
        .containsExactly("full", "compact", "left-call", "right-call");
  }

  @Test
  void approvesWithSeparationOfDutiesAndExcludesInactiveTime() {
    AiSkillExecution execution = controlledExecution(
        SkillExecutionStatus.WAITING_APPROVAL
    );
    Instant originalDeadline = Instant.now().plusSeconds(60);
    execution.setDeadlineAt(originalDeadline);
    execution.setInactiveSince(Instant.now().minusSeconds(30));
    execution.setApprovalRequired(true);
    execution.setSelfApprovalAllowed(false);
    execution.setRequestedBy("requester-a");
    when(scopeAccessPolicy.currentManagementScope())
        .thenReturn(new ScopeAssignment(AiResourceScope.SYSTEM, null));
    when(skillRepository.findActiveById("skill-a"))
        .thenReturn(Optional.of(skill()));
    when(executionRepository.findActiveByIdForUpdate("execution-a"))
        .thenReturn(Optional.of(execution));
    when(stepRepository.findAllActiveByExecutionId("execution-a"))
        .thenReturn(List.of());
    AuthorizationContext context = new AuthorizationContext();
    context.setUserId("approver-a");

    try (MockedStatic<AuthorizationContextHolder> holder =
             mockStatic(AuthorizationContextHolder.class)) {
      holder.when(AuthorizationContextHolder::getContext).thenReturn(context);
      AiSkillExecution result = service.approve(
          "skill-a",
          "execution-a",
          new SkillExecutionDecisionRequest("Looks safe")
      );

      assertThat(result.getStatus()).isEqualTo(SkillExecutionStatus.PENDING);
      assertThat(result.getApprovedBy()).isEqualTo("approver-a");
      assertThat(result.getApprovalComment()).isEqualTo("Looks safe");
      assertThat(result.getDeadlineAt()).isAfterOrEqualTo(
          originalDeadline.plus(Duration.ofSeconds(29))
      );
      assertThat(result.getInactiveSince()).isNull();
    }
  }

  @Test
  void pausesAndResumesPendingExecutionWithoutConsumingItsDeadline() {
    AiSkillExecution execution = controlledExecution(
        SkillExecutionStatus.PENDING
    );
    Instant originalDeadline = Instant.now().plusSeconds(60);
    execution.setDeadlineAt(originalDeadline);
    when(scopeAccessPolicy.currentManagementScope())
        .thenReturn(new ScopeAssignment(AiResourceScope.SYSTEM, null));
    when(skillRepository.findActiveById("skill-a"))
        .thenReturn(Optional.of(skill()));
    when(executionRepository.findActiveByIdForUpdate("execution-a"))
        .thenReturn(Optional.of(execution));
    when(stepRepository.findAllActiveByExecutionId("execution-a"))
        .thenReturn(List.of());

    AiSkillExecution paused = service.pause(
        "skill-a",
        "execution-a",
        new SkillExecutionPauseRequest("Operator maintenance")
    );
    assertThat(paused.getStatus()).isEqualTo(SkillExecutionStatus.PAUSED);
    assertThat(paused.getPauseRequested()).isTrue();
    assertThat(paused.getInactiveSince()).isNotNull();
    execution.setInactiveSince(Instant.now().minusSeconds(10));

    AiSkillExecution resumed = service.resume("skill-a", "execution-a");

    assertThat(resumed.getStatus()).isEqualTo(SkillExecutionStatus.PENDING);
    assertThat(resumed.getPauseRequested()).isFalse();
    assertThat(resumed.getDeadlineAt()).isAfterOrEqualTo(
        originalDeadline.plus(Duration.ofSeconds(9))
    );
    assertThat(resumed.getInactiveSince()).isNull();
  }

  @Test
  void rejectsSelfApprovalUnlessTheImmutablePolicyAllowsIt() {
    AiSkillExecution execution = controlledExecution(
        SkillExecutionStatus.WAITING_APPROVAL
    );
    execution.setApprovalRequired(true);
    execution.setSelfApprovalAllowed(false);
    execution.setRequestedBy("requester-a");
    when(scopeAccessPolicy.currentManagementScope())
        .thenReturn(new ScopeAssignment(AiResourceScope.SYSTEM, null));
    when(skillRepository.findActiveById("skill-a"))
        .thenReturn(Optional.of(skill()));
    when(executionRepository.findActiveByIdForUpdate("execution-a"))
        .thenReturn(Optional.of(execution));
    AuthorizationContext context = new AuthorizationContext();
    context.setUserId("requester-a");

    try (MockedStatic<AuthorizationContextHolder> holder =
             mockStatic(AuthorizationContextHolder.class)) {
      holder.when(AuthorizationContextHolder::getContext).thenReturn(context);
      assertThatThrownBy(() -> service.approve(
          "skill-a",
          "execution-a",
          new SkillExecutionDecisionRequest(null)
      )).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("cannot approve or reject");
    }
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

  private static AiSkillExecution controlledExecution(
      final SkillExecutionStatus status
  ) {
    AiSkillExecution execution = new AiSkillExecution();
    execution.setId("execution-a");
    execution.setSkillId("skill-a");
    execution.setSkillVersionId("version-a");
    execution.setScopeType(AiResourceScope.SYSTEM);
    execution.setStatus(status);
    execution.setApprovalRequired(false);
    execution.setSelfApprovalAllowed(false);
    execution.setPauseRequested(false);
    execution.setInputJson("{}");
    execution.setAttemptCount(0);
    execution.setLeaseToken(0);
    return execution;
  }
}
