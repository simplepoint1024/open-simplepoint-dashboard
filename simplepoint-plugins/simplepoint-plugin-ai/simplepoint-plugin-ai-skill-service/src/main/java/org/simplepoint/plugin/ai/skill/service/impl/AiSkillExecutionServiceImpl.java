package org.simplepoint.plugin.ai.skill.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDefinition;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecution;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecutionStep;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillPromptBinding;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillResourceBinding;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillToolBinding;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillVersion;
import org.simplepoint.plugin.ai.skill.api.model.SkillAgentExecutionCommand;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionApprovalPolicy;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionBudget;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionDecisionRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionPauseRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStartRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStepStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillVersionStatus;
import org.simplepoint.plugin.ai.skill.api.properties.SkillExecutionProperties;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDefinitionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillExecutionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillExecutionStepRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillPromptBindingRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillResourceBindingRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillToolBindingRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillVersionRepository;
import org.simplepoint.plugin.ai.skill.api.service.AiSkillExecutionService;
import org.simplepoint.plugin.ai.skill.service.support.SkillApprovalPolicy;
import org.simplepoint.plugin.ai.skill.service.support.SkillBudgetPolicy;
import org.simplepoint.plugin.ai.skill.service.support.SkillJsonSchemaValidator;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler.ExecutableNode;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler.PromptNode;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler.ResourceNode;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler.ToolNode;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler.WorkflowBindings;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler.WorkflowPlan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scope-aware submission and query service for durable Skill workflows.
 */
@Service
public class AiSkillExecutionServiceImpl implements AiSkillExecutionService {

  private static final TypeReference<Map<String, Object>> MAP_TYPE =
      new TypeReference<>() {
      };

  private final AiSkillDefinitionRepository skillRepository;

  private final AiSkillVersionRepository versionRepository;

  private final AiSkillToolBindingRepository bindingRepository;

  private final AiSkillPromptBindingRepository promptBindingRepository;

  private final AiSkillResourceBindingRepository resourceBindingRepository;

  private final AiSkillExecutionRepository executionRepository;

  private final AiSkillExecutionStepRepository stepRepository;

  private final AiScopeAccessPolicy scopeAccessPolicy;

  private final SkillJsonSchemaValidator schemaValidator;

  private final SkillWorkflowPlanCompiler workflowPlanCompiler;

  private final SkillBudgetPolicy budgetPolicy;

  private final SkillApprovalPolicy approvalPolicy;

  private final SkillExecutionProperties properties;

  private final ObjectMapper objectMapper;

  private final ObjectMapper canonicalMapper;

  /**
   * Creates the durable Skill execution service.
   */
  public AiSkillExecutionServiceImpl(
      final AiSkillDefinitionRepository skillRepository,
      final AiSkillVersionRepository versionRepository,
      final AiSkillToolBindingRepository bindingRepository,
      final AiSkillPromptBindingRepository promptBindingRepository,
      final AiSkillResourceBindingRepository resourceBindingRepository,
      final AiSkillExecutionRepository executionRepository,
      final AiSkillExecutionStepRepository stepRepository,
      final AiScopeAccessPolicy scopeAccessPolicy,
      final SkillJsonSchemaValidator schemaValidator,
      final SkillWorkflowPlanCompiler workflowPlanCompiler,
      final SkillBudgetPolicy budgetPolicy,
      final SkillApprovalPolicy approvalPolicy,
      final SkillExecutionProperties properties,
      final ObjectMapper objectMapper
  ) {
    this.skillRepository = skillRepository;
    this.versionRepository = versionRepository;
    this.bindingRepository = bindingRepository;
    this.promptBindingRepository = promptBindingRepository;
    this.resourceBindingRepository = resourceBindingRepository;
    this.executionRepository = executionRepository;
    this.stepRepository = stepRepository;
    this.scopeAccessPolicy = scopeAccessPolicy;
    this.schemaValidator = schemaValidator;
    this.workflowPlanCompiler = workflowPlanCompiler;
    this.budgetPolicy = budgetPolicy;
    this.approvalPolicy = approvalPolicy;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.canonicalMapper = objectMapper.copy()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiSkillExecution start(
      final String skillId,
      final SkillExecutionStartRequest request
  ) {
    if (request == null) {
      throw new IllegalArgumentException(
          "Skill execution request must not be null"
      );
    }
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiSkillDefinition skill = requireSkillForUpdate(skillId, scope);
    if (!Boolean.TRUE.equals(skill.getEnabled())
        || skill.getActiveVersionId() == null) {
      throw new IllegalStateException("Skill has no active published version");
    }
    AiSkillVersion version = versionRepository.findActiveById(
        skill.getActiveVersionId()
    ).filter(candidate -> skill.getId().equals(candidate.getSkillId()))
        .orElseThrow(() -> new IllegalStateException(
            "Active Skill version does not exist"
        ));
    if (version.getStatus() != SkillVersionStatus.PUBLISHED) {
      throw new IllegalStateException("Active Skill version is not published");
    }
    return startVersion(
        skill,
        version,
        scope,
        currentUserId(),
        request.idempotencyKey(),
        request.input()
    );
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiSkillExecution startVersionForAgent(
      final SkillAgentExecutionCommand command
  ) {
    if (command == null || command.executionScope() == null) {
      throw new IllegalArgumentException(
          "Agent Skill execution command must not be null"
      );
    }
    ScopeAssignment scope = new ScopeAssignment(
        command.executionScope(),
        command.tenantId()
    );
    AiSkillDefinition skill = skillRepository.findActiveByIdForUpdate(
        required(command.skillId(), "Skill ID", 64)
    ).orElseThrow(() -> new IllegalArgumentException("Skill does not exist"));
    if (!scopeAccessPolicy.canUseResourceFromScope(
        skill.getScopeType(),
        skill.getTenantId(),
        scope.scopeType(),
        scope.tenantId()
    ) || !Boolean.TRUE.equals(skill.getEnabled())) {
      throw new IllegalArgumentException(
          "Skill does not exist or Agent scope cannot use it"
      );
    }
    AiSkillVersion version = versionRepository.findActiveByIdAndSkillId(
        required(command.skillVersionId(), "Skill version ID", 64),
        skill.getId()
    ).orElseThrow(() -> new IllegalArgumentException(
        "Pinned Skill version does not exist"
    ));
    if (version.getStatus() != SkillVersionStatus.PUBLISHED) {
      throw new IllegalStateException(
          "Pinned Skill version is not published"
      );
    }
    if (!required(
        command.expectedContentHash(),
        "Skill version content hash",
        64
    ).equals(version.getContentHash())) {
      throw new IllegalStateException(
          "Pinned Skill version content hash changed"
      );
    }
    return startVersion(
        skill,
        version,
        scope,
        command.requestedBy(),
        command.idempotencyKey(),
        command.input()
    );
  }

  private AiSkillExecution startVersion(
      final AiSkillDefinition skill,
      final AiSkillVersion version,
      final ScopeAssignment scope,
      final String requestedBy,
      final String rawIdempotencyKey,
      final Map<String, Object> rawInput
  ) {
    String idempotencyKey = requireIdempotencyKey(rawIdempotencyKey);
    String idempotencyHash = sha256(idempotencyKey);
    Map<String, Object> input = rawInput == null
        ? Map.of() : new LinkedHashMap<>(rawInput);
    String inputJson = writeJson(input);
    assertPayloadSize(inputJson, "Skill execution input");
    schemaValidator.validate(
        readMap(version.getInputSchemaJson(), "Skill input Schema"),
        input,
        "Skill input"
    );
    Optional<AiSkillExecution> existing =
        executionRepository.findActiveByIdempotency(
            skill.getId(),
            scope.scopeType(),
            scope.tenantId(),
            idempotencyHash
        );
    if (existing.isPresent()) {
      AiSkillExecution execution = existing.orElseThrow();
      if (!sha256(inputJson).equals(execution.getInputHash())) {
        throw new IllegalArgumentException(
            "Idempotency key was already used with different input"
        );
      }
      return decorate(execution);
    }

    Map<String, Object> workflow = readMap(
        version.getWorkflowJson(),
        "Skill workflow"
    );
    Map<String, AiSkillToolBinding> bindings = new HashMap<>();
    bindingRepository.findAllActiveBySkillVersionId(version.getId())
        .forEach(binding -> bindings.put(binding.getToolAlias(), binding));
    Map<String, AiSkillPromptBinding> promptBindings = new HashMap<>();
    promptBindingRepository.findAllActiveBySkillVersionId(version.getId())
        .forEach(binding ->
            promptBindings.put(binding.getPromptAlias(), binding));
    Map<String, AiSkillResourceBinding> resourceBindings = new HashMap<>();
    resourceBindingRepository.findAllActiveBySkillVersionId(version.getId())
        .forEach(binding ->
            resourceBindings.put(binding.getResourceAlias(), binding));
    WorkflowPlan workflowPlan = workflowPlanCompiler.compile(
        workflow,
        new WorkflowBindings(
            bindings.keySet(),
            promptBindings.keySet(),
            resourceBindings.keySet()
        )
    );
    SkillExecutionBudget budget = budgetPolicy.read(
        version.getBudgetJson(),
        workflowPlan.maximumToolCalls()
    );
    final SkillExecutionApprovalPolicy approval =
        approvalPolicy.readManifest(version.getManifestJson());
    long initialPayloadBytes = payloadBytes(inputJson);
    if (initialPayloadBytes > budget.maximumPayloadBytes()) {
      throw new IllegalArgumentException(
          "Skill execution input exceeds the version payload budget"
      );
    }
    AiSkillExecution execution = new AiSkillExecution();
    execution.setSkillId(skill.getId());
    execution.setSkillVersionId(version.getId());
    execution.setScopeType(scope.scopeType());
    execution.setTenantId(scope.tenantId());
    execution.setIdempotencyKeyHash(idempotencyHash);
    execution.setInputHash(sha256(inputJson));
    execution.setInputJson(inputJson);
    execution.setOutputTemplateJson(workflow.containsKey("output")
        ? writeJson(workflow.get("output")) : null);
    execution.setWorkflowPlanJson(writeJson(workflow));
    execution.setOutputSchemaJson(version.getOutputSchemaJson());
    Instant submittedAt = Instant.now();
    execution.setStatus(approval.required()
        ? SkillExecutionStatus.WAITING_APPROVAL
        : SkillExecutionStatus.PENDING);
    execution.setApprovalRequired(approval.required());
    execution.setSelfApprovalAllowed(approval.allowSelfApproval());
    execution.setApprovalInstructions(approval.instructions());
    execution.setApprovalRequestedAt(
        approval.required() ? submittedAt : null
    );
    execution.setPauseRequested(false);
    execution.setInactiveSince(approval.required() ? submittedAt : null);
    execution.setAttemptCount(0);
    execution.setLeaseToken(0);
    execution.setMaximumToolCalls(budget.maximumToolCalls());
    execution.setMaximumDurationSeconds(budget.maximumDurationSeconds());
    execution.setMaximumPayloadBytes(budget.maximumPayloadBytes());
    execution.setConsumedToolCalls(0);
    execution.setConsumedPayloadBytes(initialPayloadBytes);
    execution.setDeadlineAt(
        submittedAt.plusSeconds(budget.maximumDurationSeconds())
    );
    execution.setRequestedBy(requestedBy);
    execution = executionRepository.save(execution);

    List<AiSkillExecutionStep> savedSteps = new ArrayList<>();
    List<ExecutableNode> workflowSteps = workflowPlan.executableSteps();
    for (int index = 0; index < workflowSteps.size(); index++) {
      AiSkillExecutionStep step = createExecutionStep(
          execution.getId(),
          index,
          workflowSteps.get(index),
          bindings,
          promptBindings,
          resourceBindings
      );
      step.setStatus(SkillExecutionStepStatus.PENDING);
      step.setAttemptCount(0);
      savedSteps.add(stepRepository.save(step));
    }
    return decorate(execution, savedSteps);
  }

  private AiSkillExecutionStep createExecutionStep(
      final String executionId,
      final int order,
      final ExecutableNode source,
      final Map<String, AiSkillToolBinding> toolBindings,
      final Map<String, AiSkillPromptBinding> promptBindings,
      final Map<String, AiSkillResourceBinding> resourceBindings
  ) {
    AiSkillExecutionStep step = new AiSkillExecutionStep();
    step.setExecutionId(executionId);
    step.setStepId(source.id());
    step.setStepOrder(order);
    switch (source) {
      case ToolNode tool -> {
        AiSkillToolBinding binding = toolBindings.get(tool.toolAlias());
        if (binding == null) {
          throw new IllegalStateException(
              "Workflow Tool binding does not exist: " + tool.toolAlias()
          );
        }
        step.setStepType("tool");
        step.setBindingId(binding.getId());
        step.setCapabilityAlias(binding.getToolAlias());
        step.setMcpServerId(binding.getMcpServerId());
        step.setCapabilitySnapshotId(binding.getCapabilitySnapshotId());
        step.setCapabilityName(binding.getToolName());
        step.setCapabilitySchemaHash(binding.getInputSchemaHash());
        step.setCapabilityTemplate(false);
        step.setInputTemplateJson(tool.argumentsTemplate() == null
            ? null : writeJson(tool.argumentsTemplate()));
      }
      case PromptNode prompt -> {
        AiSkillPromptBinding binding = promptBindings.get(
            prompt.promptAlias()
        );
        if (binding == null) {
          throw new IllegalStateException(
              "Workflow Prompt binding does not exist: "
                  + prompt.promptAlias()
          );
        }
        step.setStepType("prompt");
        step.setBindingId(binding.getId());
        step.setCapabilityAlias(binding.getPromptAlias());
        step.setMcpServerId(binding.getMcpServerId());
        step.setCapabilitySnapshotId(binding.getCapabilitySnapshotId());
        step.setCapabilityName(binding.getPromptName());
        step.setCapabilitySchemaHash(binding.getDescriptorHash());
        step.setCapabilityTemplate(false);
        step.setInputTemplateJson(prompt.argumentsTemplate() == null
            ? null : writeJson(prompt.argumentsTemplate()));
      }
      case ResourceNode resource -> {
        AiSkillResourceBinding binding = resourceBindings.get(
            resource.resourceAlias()
        );
        if (binding == null) {
          throw new IllegalStateException(
              "Workflow Resource binding does not exist: "
                  + resource.resourceAlias()
          );
        }
        if (Boolean.TRUE.equals(binding.getResourceTemplate())
            && resource.uriTemplate() == null) {
          throw new IllegalArgumentException(
              "Resource Template workflow step must define uri"
          );
        }
        step.setStepType("resource");
        step.setBindingId(binding.getId());
        step.setCapabilityAlias(binding.getResourceAlias());
        step.setMcpServerId(binding.getMcpServerId());
        step.setCapabilitySnapshotId(binding.getCapabilitySnapshotId());
        step.setCapabilityName(binding.getResourceSelector());
        step.setCapabilitySchemaHash(binding.getDescriptorHash());
        step.setCapabilityTemplate(
            Boolean.TRUE.equals(binding.getResourceTemplate())
        );
        step.setInputTemplateJson(resource.uriTemplate() == null
            ? null : writeJson(resource.uriTemplate()));
      }
    }
    return step;
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AiSkillExecution> findAll(
      final String skillId,
      final Pageable pageable
  ) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiSkillDefinition skill = requireSkill(skillId, scope);
    return executionRepository.findAllActiveBySkillAndScope(
        skill.getId(),
        scope.scopeType(),
        scope.tenantId(),
        pageable
    ).map(this::decorate);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AiSkillExecution> find(
      final String skillId,
      final String executionId
  ) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiSkillDefinition skill = requireSkill(skillId, scope);
    return executionRepository.findActiveById(
        required(executionId, "Skill execution ID", 64)
    ).filter(execution -> skill.getId().equals(execution.getSkillId()))
        .filter(execution -> execution.getScopeType() == scope.scopeType())
        .filter(execution -> java.util.Objects.equals(
            execution.getTenantId(),
            scope.tenantId()
        ))
        .map(this::decorate);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiSkillExecution approve(
      final String skillId,
      final String executionId,
      final SkillExecutionDecisionRequest request
  ) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiSkillDefinition skill = requireSkill(skillId, scope);
    AiSkillExecution execution = requireExecutionForUpdate(
        skill,
        scope,
        executionId
    );
    if (execution.getApprovedAt() != null) {
      return decorate(execution);
    }
    assertApprovalPending(execution);
    String actor = requireCurrentUserId();
    assertSelfApprovalAllowed(execution, actor);
    Instant now = Instant.now();
    execution.setApprovedAt(now);
    execution.setApprovedBy(actor);
    execution.setApprovalComment(optionalComment(
        request == null ? null : request.comment()
    ));
    if (execution.getStatus() == SkillExecutionStatus.WAITING_APPROVAL) {
      activate(execution, now);
    }
    executionRepository.save(execution);
    return decorate(execution);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiSkillExecution reject(
      final String skillId,
      final String executionId,
      final SkillExecutionDecisionRequest request
  ) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiSkillDefinition skill = requireSkill(skillId, scope);
    AiSkillExecution execution = requireExecutionForUpdate(
        skill,
        scope,
        executionId
    );
    if (execution.getStatus() == SkillExecutionStatus.REJECTED) {
      return decorate(execution);
    }
    assertApprovalPending(execution);
    String actor = requireCurrentUserId();
    assertSelfApprovalAllowed(execution, actor);
    if (execution.getStatus() != SkillExecutionStatus.WAITING_APPROVAL
        && execution.getStatus() != SkillExecutionStatus.PAUSED) {
      throw new IllegalStateException(
          "Skill execution is not waiting for an approval decision"
      );
    }
    Instant now = Instant.now();
    execution.setRejectedAt(now);
    execution.setRejectedBy(actor);
    execution.setRejectionReason(optionalComment(
        request == null ? null : request.comment()
    ));
    execution.setStatus(SkillExecutionStatus.REJECTED);
    execution.setCompletedAt(now);
    execution.setInactiveSince(null);
    execution.setPauseRequested(false);
    clearLease(execution);
    executionRepository.save(execution);
    return decorate(execution);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiSkillExecution pause(
      final String skillId,
      final String executionId,
      final SkillExecutionPauseRequest request
  ) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiSkillDefinition skill = requireSkill(skillId, scope);
    AiSkillExecution execution = requireExecutionForUpdate(
        skill,
        scope,
        executionId
    );
    if (execution.getStatus() == SkillExecutionStatus.PAUSED
        || Boolean.TRUE.equals(execution.getPauseRequested())) {
      return decorate(execution);
    }
    if (isTerminal(execution.getStatus())) {
      throw new IllegalStateException(
          "Terminal Skill execution cannot be paused"
      );
    }
    Instant now = Instant.now();
    execution.setPauseRequested(true);
    execution.setPauseRequestedAt(now);
    execution.setPauseRequestedBy(currentUserId());
    execution.setPauseReason(optionalComment(
        request == null ? null : request.reason()
    ));
    if (execution.getStatus() != SkillExecutionStatus.RUNNING) {
      execution.setStatus(SkillExecutionStatus.PAUSED);
      execution.setPausedAt(now);
      if (execution.getInactiveSince() == null) {
        execution.setInactiveSince(now);
      }
      clearLease(execution);
    }
    executionRepository.save(execution);
    return decorate(execution);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiSkillExecution resume(
      final String skillId,
      final String executionId
  ) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiSkillDefinition skill = requireSkill(skillId, scope);
    AiSkillExecution execution = requireExecutionForUpdate(
        skill,
        scope,
        executionId
    );
    if (execution.getStatus() != SkillExecutionStatus.PAUSED) {
      throw new IllegalStateException(
          "Only a paused Skill execution can be resumed"
      );
    }
    Instant now = Instant.now();
    execution.setPauseRequested(false);
    execution.setResumedAt(now);
    execution.setResumedBy(currentUserId());
    if (Boolean.TRUE.equals(execution.getApprovalRequired())
        && execution.getApprovedAt() == null) {
      execution.setStatus(SkillExecutionStatus.WAITING_APPROVAL);
    } else {
      activate(execution, now);
    }
    executionRepository.save(execution);
    return decorate(execution);
  }

  private AiSkillDefinition requireSkill(
      final String skillId,
      final ScopeAssignment scope
  ) {
    AiSkillDefinition skill = skillRepository.findActiveById(
        required(skillId, "Skill ID", 64)
    ).orElseThrow(() -> new IllegalArgumentException("Skill does not exist"));
    if (skill.getScopeType() != scope.scopeType()
        || !java.util.Objects.equals(skill.getTenantId(), scope.tenantId())) {
      throw new IllegalArgumentException("Skill does not exist");
    }
    return skill;
  }

  private AiSkillDefinition requireSkillForUpdate(
      final String skillId,
      final ScopeAssignment scope
  ) {
    AiSkillDefinition skill = skillRepository.findActiveByIdForUpdate(
        required(skillId, "Skill ID", 64)
    ).orElseThrow(() -> new IllegalArgumentException("Skill does not exist"));
    if (skill.getScopeType() != scope.scopeType()
        || !java.util.Objects.equals(skill.getTenantId(), scope.tenantId())) {
      throw new IllegalArgumentException("Skill does not exist");
    }
    return skill;
  }

  private AiSkillExecution requireExecutionForUpdate(
      final AiSkillDefinition skill,
      final ScopeAssignment scope,
      final String executionId
  ) {
    AiSkillExecution execution = executionRepository.findActiveByIdForUpdate(
        required(executionId, "Skill execution ID", 64)
    ).orElseThrow(() -> new IllegalArgumentException(
        "Skill execution does not exist"
    ));
    if (!skill.getId().equals(execution.getSkillId())
        || execution.getScopeType() != scope.scopeType()
        || !java.util.Objects.equals(
            execution.getTenantId(),
            scope.tenantId()
        )) {
      throw new IllegalArgumentException("Skill execution does not exist");
    }
    return execution;
  }

  private static void assertApprovalPending(
      final AiSkillExecution execution
  ) {
    if (!Boolean.TRUE.equals(execution.getApprovalRequired())) {
      throw new IllegalStateException(
          "Skill execution does not require approval"
      );
    }
    if (execution.getApprovedAt() != null
        || execution.getRejectedAt() != null
        || isTerminal(execution.getStatus())) {
      throw new IllegalStateException(
          "Skill execution approval was already decided"
      );
    }
  }

  private static void assertSelfApprovalAllowed(
      final AiSkillExecution execution,
      final String actor
  ) {
    if (!Boolean.TRUE.equals(execution.getSelfApprovalAllowed())
        && actor.equals(execution.getRequestedBy())) {
      throw new IllegalStateException(
          "Skill execution requester cannot approve or reject this execution"
      );
    }
  }

  private static void activate(
      final AiSkillExecution execution,
      final Instant now
  ) {
    if (execution.getInactiveSince() != null
        && execution.getDeadlineAt() != null
        && now.isAfter(execution.getInactiveSince())) {
      Duration inactive = Duration.between(execution.getInactiveSince(), now);
      execution.setDeadlineAt(execution.getDeadlineAt().plus(inactive));
    }
    execution.setInactiveSince(null);
    execution.setStatus(SkillExecutionStatus.PENDING);
  }

  private static boolean isTerminal(final SkillExecutionStatus status) {
    return status == SkillExecutionStatus.SUCCEEDED
        || status == SkillExecutionStatus.FAILED
        || status == SkillExecutionStatus.REJECTED
        || status == SkillExecutionStatus.CANCELLED;
  }

  private static void clearLease(final AiSkillExecution execution) {
    execution.setLeaseOwner(null);
    execution.setLeaseExpiresAt(null);
  }

  private static String optionalComment(final String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.replaceAll("\\s+", " ").trim();
    if (normalized.length() > 1024) {
      throw new IllegalArgumentException(
          "Skill execution decision comment is too long"
      );
    }
    return normalized;
  }

  private AiSkillExecution decorate(final AiSkillExecution execution) {
    return decorate(
        execution,
        stepRepository.findAllActiveByExecutionId(execution.getId())
    );
  }

  private AiSkillExecution decorate(
      final AiSkillExecution execution,
      final List<AiSkillExecutionStep> steps
  ) {
    execution.setInput(readMap(execution.getInputJson(), "Skill input"));
    execution.setOutput(execution.getOutputJson() == null
        ? null : readValue(execution.getOutputJson(), "Skill output"));
    steps.forEach(step -> {
      step.setInput(step.getInputJson() == null
          ? null : readMap(step.getInputJson(), "Skill step input"));
      step.setOutput(step.getOutputJson() == null
          ? null : readValue(step.getOutputJson(), "Skill step output"));
    });
    execution.setSteps(List.copyOf(steps));
    return execution;
  }

  private Map<String, Object> readMap(
      final String json,
      final String label
  ) {
    try {
      return objectMapper.readValue(json, MAP_TYPE);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(label + " is corrupted", ex);
    }
  }

  private Object readValue(final String json, final String label) {
    try {
      return objectMapper.readValue(json, Object.class);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(label + " is corrupted", ex);
    }
  }

  private String writeJson(final Object value) {
    try {
      return canonicalMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException(
          "Skill workflow value is not valid JSON",
          ex
      );
    }
  }

  private void assertPayloadSize(final String json, final String label) {
    int maximum = properties.getMaximumPayloadBytes() == null
        ? 256 * 1024 : properties.getMaximumPayloadBytes();
    if (maximum < 1024
        || json.getBytes(StandardCharsets.UTF_8).length > maximum) {
      throw new IllegalArgumentException(label + " is too large");
    }
  }

  private static long payloadBytes(final String json) {
    return json.getBytes(StandardCharsets.UTF_8).length;
  }

  private static String requireIdempotencyKey(final String value) {
    return required(value, "Skill execution idempotency key", 128);
  }

  private static String required(
      final Object value,
      final String label,
      final int maximumLength
  ) {
    String normalized = value == null ? null : String.valueOf(value).trim();
    if (normalized == null || normalized.isEmpty()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    if (normalized.length() > maximumLength) {
      throw new IllegalArgumentException(label + " is too long");
    }
    return normalized;
  }

  private static String currentUserId() {
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    return context == null ? null : context.getUserId();
  }

  private static String requireCurrentUserId() {
    String userId = currentUserId();
    if (userId == null || userId.isBlank()) {
      throw new IllegalStateException(
          "Authenticated user is required for Skill approval"
      );
    }
    return userId;
  }

  private static String sha256(final String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }
}
