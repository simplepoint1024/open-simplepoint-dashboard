package org.simplepoint.plugin.ai.skill.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDefinition;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecution;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecutionStep;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionCancelRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionEventType;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionSource;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStepStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillPinnedChildCancelCommand;
import org.simplepoint.plugin.ai.skill.api.properties.SkillExecutionProperties;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDefinitionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDraftRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDraftRevisionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillExecutionEventRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillExecutionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillExecutionStepRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillPromptBindingRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillResourceBindingRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillToolBindingRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillVersionRepository;
import org.simplepoint.plugin.ai.skill.service.execution.AiSkillExecutionEventPublisher;
import org.simplepoint.plugin.ai.skill.service.execution.SkillDraftCapabilityResolver;
import org.simplepoint.plugin.ai.skill.service.support.SkillApprovalPolicy;
import org.simplepoint.plugin.ai.skill.service.support.SkillBudgetPolicy;
import org.simplepoint.plugin.ai.skill.service.support.SkillJsonSchemaValidator;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowConditionEvaluator;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowTemplateResolver;

class AiSkillPinnedChildCancellationTest {

  private AiSkillDefinitionRepository skillRepository;

  private AiSkillExecutionRepository executionRepository;

  private AiSkillExecutionStepRepository stepRepository;

  private AiScopeAccessPolicy scopeAccessPolicy;

  private AiSkillExecutionEventPublisher eventPublisher;

  private AiSkillExecutionServiceImpl service;

  @BeforeEach
  void setUp() {
    skillRepository = mock(AiSkillDefinitionRepository.class);
    executionRepository = mock(AiSkillExecutionRepository.class);
    stepRepository = mock(AiSkillExecutionStepRepository.class);
    scopeAccessPolicy = mock(AiScopeAccessPolicy.class);
    eventPublisher = mock(AiSkillExecutionEventPublisher.class);
    ObjectMapper objectMapper = new ObjectMapper();
    SkillExecutionProperties properties = new SkillExecutionProperties();
    SkillWorkflowTemplateResolver templateResolver =
        new SkillWorkflowTemplateResolver();
    service = new AiSkillExecutionServiceImpl(
        skillRepository,
        mock(AiSkillVersionRepository.class),
        mock(AiSkillDraftRepository.class),
        mock(AiSkillDraftRevisionRepository.class),
        mock(AiSkillToolBindingRepository.class),
        mock(AiSkillPromptBindingRepository.class),
        mock(AiSkillResourceBindingRepository.class),
        executionRepository,
        mock(AiSkillExecutionEventRepository.class),
        stepRepository,
        scopeAccessPolicy,
        new SkillJsonSchemaValidator(),
        new SkillWorkflowPlanCompiler(
            templateResolver,
            new SkillWorkflowConditionEvaluator(templateResolver)
        ),
        new SkillBudgetPolicy(properties, objectMapper),
        new SkillApprovalPolicy(objectMapper),
        mock(SkillDraftCapabilityResolver.class),
        eventPublisher,
        properties,
        objectMapper
    );
  }

  @Test
  void rejectsResourceMismatchWithoutUsingManagementScope() {
    AiSkillExecution execution = pinnedExecution(
        SkillExecutionStatus.PENDING
    );
    stubExecution(execution);

    assertBoundaryRejected(command(
        "other-skill", "version-a", AiResourceScope.TENANT,
        "tenant-a", "parent-a:node-a"
    ));
  }

  @Test
  void rejectsVersionMismatchWithoutUsingManagementScope() {
    AiSkillExecution execution = pinnedExecution(
        SkillExecutionStatus.PENDING
    );
    stubExecution(execution);

    assertBoundaryRejected(command(
        "skill-a", "other-version", AiResourceScope.TENANT,
        "tenant-a", "parent-a:node-a"
    ));
  }

  @Test
  void rejectsScopeMismatchWithoutUsingManagementScope() {
    AiSkillExecution execution = pinnedExecution(
        SkillExecutionStatus.PENDING
    );
    stubExecution(execution);

    assertBoundaryRejected(command(
        "skill-a", "version-a", AiResourceScope.SYSTEM,
        null, "parent-a:node-a"
    ));
  }

  @Test
  void rejectsTenantMismatchWithoutUsingManagementScope() {
    AiSkillExecution execution = pinnedExecution(
        SkillExecutionStatus.PENDING
    );
    stubExecution(execution);

    assertBoundaryRejected(command(
        "skill-a", "version-a", AiResourceScope.TENANT,
        "tenant-b", "parent-a:node-a"
    ));
  }

  @Test
  void rejectsIdempotencyKeyMismatchWithoutUsingManagementScope() {
    AiSkillExecution execution = pinnedExecution(
        SkillExecutionStatus.PENDING
    );
    stubExecution(execution);

    assertBoundaryRejected(command(
        "skill-a", "version-a", AiResourceScope.TENANT,
        "tenant-a", "different-parent:node-a"
    ));
  }

  @Test
  void rejectsDraftExecutionEvenWhenAllOtherFieldsMatch() {
    AiSkillExecution execution = pinnedExecution(
        SkillExecutionStatus.PENDING
    );
    execution.setSourceType(SkillExecutionSource.DRAFT);
    stubExecution(execution);

    assertBoundaryRejected(command());
  }

  @Test
  void runningExecutionReceivesOneCooperativeCancelRequest() {
    AiSkillExecution execution = pinnedExecution(
        SkillExecutionStatus.RUNNING
    );
    execution.setCurrentStepId("step-a");
    execution.setLeaseOwner("skill-worker-a");
    execution.setLeaseExpiresAt(Instant.now().plusSeconds(60));
    stubExecution(execution);
    when(stepRepository.findAllActiveByExecutionId("execution-a"))
        .thenReturn(List.of());

    AiSkillExecution first = service.cancelPinnedChild(command());
    Instant requestedAt = first.getCancelRequestedAt();
    AiSkillExecution repeated = service.cancelPinnedChild(command());

    assertThat(repeated).isSameAs(execution);
    assertThat(execution.getStatus()).isEqualTo(SkillExecutionStatus.RUNNING);
    assertThat(execution.getCancelRequested()).isTrue();
    assertThat(execution.getCancelRequestedAt()).isEqualTo(requestedAt);
    assertThat(execution.getCancelRequestedBy()).isEqualTo("parent-runtime-a");
    assertThat(execution.getCancelReason())
        .isEqualTo("Parent execution was cancelled");
    assertThat(execution.getLeaseOwner()).isEqualTo("skill-worker-a");
    assertThat(execution.getCompletedAt()).isNull();
    verify(executionRepository, times(1)).save(execution);
    verify(eventPublisher, times(1)).publish(
        eq(execution),
        eq(SkillExecutionEventType.CANCEL_REQUESTED),
        eq("step-a"),
        eq("parent-runtime-a"),
        eq(Map.of()),
        any(Instant.class)
    );
    verifyNoInteractions(scopeAccessPolicy);
  }

  @Test
  void pendingExecutionCancelsImmediatelyAndRepeatPreservesFacts() {
    AiSkillExecution execution = pinnedExecution(
        SkillExecutionStatus.PENDING
    );
    execution.setLeaseOwner("stale-worker-a");
    execution.setLeaseExpiresAt(Instant.now().plusSeconds(60));
    AiSkillExecutionStep step = new AiSkillExecutionStep();
    step.setId("step-row-a");
    step.setExecutionId("execution-a");
    step.setStepId("step-a");
    step.setStatus(SkillExecutionStepStatus.PENDING);
    stubExecution(execution);
    when(stepRepository.findAllActiveByExecutionId("execution-a"))
        .thenReturn(List.of(step));

    AiSkillExecution first = service.cancelPinnedChild(command());
    Instant completedAt = first.getCompletedAt();
    Instant requestedAt = first.getCancelRequestedAt();
    AiSkillExecution repeated = service.cancelPinnedChild(command());

    assertThat(repeated).isSameAs(execution);
    assertThat(execution.getStatus())
        .isEqualTo(SkillExecutionStatus.CANCELLED);
    assertThat(execution.getCompletedAt()).isEqualTo(completedAt);
    assertThat(execution.getCancelRequestedAt()).isEqualTo(requestedAt);
    assertThat(execution.getCancelRequestedBy()).isEqualTo("parent-runtime-a");
    assertThat(execution.getCancelReason())
        .isEqualTo("Parent execution was cancelled");
    assertThat(execution.getLeaseOwner()).isNull();
    assertThat(execution.getLeaseExpiresAt()).isNull();
    assertThat(step.getStatus()).isEqualTo(SkillExecutionStepStatus.SKIPPED);
    assertThat(step.getErrorCode()).isEqualTo("SKILL_EXECUTION_CANCELLED");
    verify(executionRepository, times(1)).save(execution);
    verify(stepRepository, times(1)).save(step);
    verify(eventPublisher, times(1)).publish(
        eq(execution),
        eq(SkillExecutionEventType.EXECUTION_CANCELLED),
        eq(null),
        eq("parent-runtime-a"),
        eq(Map.of()),
        any(Instant.class)
    );
    verifyNoInteractions(scopeAccessPolicy);
  }

  @Test
  void succeededExecutionIsAnIdempotentInternalNoOp() {
    AiSkillExecution execution = pinnedExecution(
        SkillExecutionStatus.SUCCEEDED
    );
    Instant completedAt = Instant.now().minusSeconds(5);
    execution.setCompletedAt(completedAt);
    stubExecution(execution);
    when(stepRepository.findAllActiveByExecutionId("execution-a"))
        .thenReturn(List.of());

    AiSkillExecution result = service.cancelPinnedChild(command());

    assertThat(result).isSameAs(execution);
    assertThat(execution.getStatus())
        .isEqualTo(SkillExecutionStatus.SUCCEEDED);
    assertThat(execution.getCompletedAt()).isEqualTo(completedAt);
    assertThat(execution.getCancelRequested()).isFalse();
    verify(executionRepository, never()).save(any());
    verifyNoInteractions(eventPublisher, scopeAccessPolicy);
  }

  @Test
  void publicCancellationKeepsExistingTerminalSemantics() {
    AiSkillExecution succeeded = pinnedExecution(
        SkillExecutionStatus.SUCCEEDED
    );
    when(scopeAccessPolicy.currentManagementScope()).thenReturn(
        new ScopeAssignment(AiResourceScope.TENANT, "tenant-a")
    );
    when(skillRepository.findActiveById("skill-a"))
        .thenReturn(Optional.of(skill()));
    when(executionRepository.findActiveByIdForUpdate("execution-a"))
        .thenReturn(Optional.of(succeeded));

    assertThatThrownBy(() -> service.cancel(
        "skill-a",
        "execution-a",
        new SkillExecutionCancelRequest("Too late")
    )).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Terminal Skill execution");

    succeeded.setStatus(SkillExecutionStatus.CANCELLED);
    when(stepRepository.findAllActiveByExecutionId("execution-a"))
        .thenReturn(List.of());
    assertThat(service.cancel(
        "skill-a",
        "execution-a",
        new SkillExecutionCancelRequest("Repeated")
    )).isSameAs(succeeded);
    verify(executionRepository, never()).save(any());
    verifyNoInteractions(eventPublisher);
  }

  private void assertBoundaryRejected(
      final SkillPinnedChildCancelCommand command
  ) {
    assertThatThrownBy(() -> service.cancelPinnedChild(command))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match cancellation command");
    verify(executionRepository, never()).save(any());
    verifyNoInteractions(eventPublisher, scopeAccessPolicy);
  }

  private void stubExecution(final AiSkillExecution execution) {
    when(executionRepository.findActiveByIdForUpdate("execution-a"))
        .thenReturn(Optional.of(execution));
  }

  private static AiSkillExecution pinnedExecution(
      final SkillExecutionStatus status
  ) {
    AiSkillExecution execution = new AiSkillExecution();
    execution.setId("execution-a");
    execution.setSkillId("skill-a");
    execution.setSkillVersionId("version-a");
    execution.setSourceType(SkillExecutionSource.PUBLISHED);
    execution.setScopeType(AiResourceScope.TENANT);
    execution.setTenantId("tenant-a");
    execution.setIdempotencyKeyHash(sha256("parent-a:node-a"));
    execution.setInputJson("{}");
    execution.setStatus(status);
    execution.setCancelRequested(false);
    execution.setPauseRequested(false);
    execution.setLeaseToken(3);
    return execution;
  }

  private static AiSkillDefinition skill() {
    AiSkillDefinition skill = new AiSkillDefinition();
    skill.setId("skill-a");
    skill.setScopeType(AiResourceScope.TENANT);
    skill.setTenantId("tenant-a");
    return skill;
  }

  private static SkillPinnedChildCancelCommand command() {
    return command(
        "skill-a", "version-a", AiResourceScope.TENANT,
        "tenant-a", "parent-a:node-a"
    );
  }

  private static SkillPinnedChildCancelCommand command(
      final String skillId,
      final String skillVersionId,
      final AiResourceScope scope,
      final String tenantId,
      final String idempotencyKey
  ) {
    return new SkillPinnedChildCancelCommand(
        skillId,
        skillVersionId,
        "execution-a",
        scope,
        tenantId,
        idempotencyKey,
        "parent-runtime-a",
        "Parent execution was cancelled"
    );
  }

  private static String sha256(final String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
