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
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.MockMode;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.NodeMock;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.TestCase;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDefinition;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDraft;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDraftRevision;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecution;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecutionEvent;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecutionStep;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillPromptBinding;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillResourceBinding;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillToolBinding;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillVersion;
import org.simplepoint.plugin.ai.skill.api.model.SkillAgentExecutionCommand;
import org.simplepoint.plugin.ai.skill.api.model.SkillDebugMode;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftDebugExecutionStartRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftMockTestRun;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftMockTestRunCase;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftMockTestRunStartRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftMockTestRunStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftValidationStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionApprovalPolicy;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionBreakpointsRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionBudget;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionCancelRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionDecisionRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionEventFeed;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionEventType;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionPauseRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionSource;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStartRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStepStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillPinnedChildCancelCommand;
import org.simplepoint.plugin.ai.skill.api.model.SkillTestAssertionResult;
import org.simplepoint.plugin.ai.skill.api.model.SkillVersionStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillWorkflowExecutionCommand;
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
import org.simplepoint.plugin.ai.skill.api.service.AiSkillExecutionService;
import org.simplepoint.plugin.ai.skill.service.execution.AiSkillExecutionEventPublisher;
import org.simplepoint.plugin.ai.skill.service.execution.SkillDraftCapabilityResolver;
import org.simplepoint.plugin.ai.skill.service.execution.SkillDraftCapabilityResolver.PromptBinding;
import org.simplepoint.plugin.ai.skill.service.execution.SkillDraftCapabilityResolver.ResolvedBindings;
import org.simplepoint.plugin.ai.skill.service.execution.SkillDraftCapabilityResolver.ResourceBinding;
import org.simplepoint.plugin.ai.skill.service.execution.SkillDraftCapabilityResolver.ToolBinding;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scope-aware submission and query service for durable Skill workflows.
 */
@Service
public class AiSkillExecutionServiceImpl implements AiSkillExecutionService {

  private static final TypeReference<Map<String, Object>> MAP_TYPE =
      new TypeReference<>() {
      };

  private static final TypeReference<List<SkillTestAssertionResult>>
      ASSERTION_RESULTS_TYPE = new TypeReference<>() {
      };

  private static final TypeReference<List<String>> STRING_LIST_TYPE =
      new TypeReference<>() {
      };

  private final AiSkillDefinitionRepository skillRepository;

  private final AiSkillVersionRepository versionRepository;

  private final AiSkillDraftRepository draftRepository;

  private final AiSkillDraftRevisionRepository draftRevisionRepository;

  private final AiSkillToolBindingRepository bindingRepository;

  private final AiSkillPromptBindingRepository promptBindingRepository;

  private final AiSkillResourceBindingRepository resourceBindingRepository;

  private final AiSkillExecutionRepository executionRepository;

  private final AiSkillExecutionEventRepository eventRepository;

  private final AiSkillExecutionStepRepository stepRepository;

  private final AiScopeAccessPolicy scopeAccessPolicy;

  private final SkillJsonSchemaValidator schemaValidator;

  private final SkillWorkflowPlanCompiler workflowPlanCompiler;

  private final SkillBudgetPolicy budgetPolicy;

  private final SkillApprovalPolicy approvalPolicy;

  private final SkillDraftCapabilityResolver draftCapabilityResolver;

  private final AiSkillExecutionEventPublisher eventPublisher;

  private final SkillExecutionProperties properties;

  private final ObjectMapper objectMapper;

  private final ObjectMapper canonicalMapper;

  /**
   * Creates the durable Skill execution service.
   */
  public AiSkillExecutionServiceImpl(
      final AiSkillDefinitionRepository skillRepository,
      final AiSkillVersionRepository versionRepository,
      final AiSkillDraftRepository draftRepository,
      final AiSkillDraftRevisionRepository draftRevisionRepository,
      final AiSkillToolBindingRepository bindingRepository,
      final AiSkillPromptBindingRepository promptBindingRepository,
      final AiSkillResourceBindingRepository resourceBindingRepository,
      final AiSkillExecutionRepository executionRepository,
      final AiSkillExecutionEventRepository eventRepository,
      final AiSkillExecutionStepRepository stepRepository,
      final AiScopeAccessPolicy scopeAccessPolicy,
      final SkillJsonSchemaValidator schemaValidator,
      final SkillWorkflowPlanCompiler workflowPlanCompiler,
      final SkillBudgetPolicy budgetPolicy,
      final SkillApprovalPolicy approvalPolicy,
      final SkillDraftCapabilityResolver draftCapabilityResolver,
      final AiSkillExecutionEventPublisher eventPublisher,
      final SkillExecutionProperties properties,
      final ObjectMapper objectMapper
  ) {
    this.skillRepository = skillRepository;
    this.versionRepository = versionRepository;
    this.draftRepository = draftRepository;
    this.draftRevisionRepository = draftRevisionRepository;
    this.bindingRepository = bindingRepository;
    this.promptBindingRepository = promptBindingRepository;
    this.resourceBindingRepository = resourceBindingRepository;
    this.executionRepository = executionRepository;
    this.eventRepository = eventRepository;
    this.stepRepository = stepRepository;
    this.scopeAccessPolicy = scopeAccessPolicy;
    this.schemaValidator = schemaValidator;
    this.workflowPlanCompiler = workflowPlanCompiler;
    this.budgetPolicy = budgetPolicy;
    this.approvalPolicy = approvalPolicy;
    this.draftCapabilityResolver = draftCapabilityResolver;
    this.eventPublisher = eventPublisher;
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
  public AiSkillExecution startDraftDebug(
      final String skillId,
      final SkillDraftDebugExecutionStartRequest request
  ) {
    if (request == null || request.revision() == null
        || request.revision() < 1) {
      throw new IllegalArgumentException(
          "Skill Draft debug revision must be greater than zero"
      );
    }
    if (request.mode() == null) {
      throw new IllegalArgumentException(
          "Skill Draft debug mode is required"
      );
    }
    if (request.mode() == SkillDebugMode.MOCK
        && (request.testCaseId() == null || request.testCaseId().isBlank())) {
      throw new IllegalArgumentException("Mock test case ID is required");
    }
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiSkillDefinition skill = requireSkillForUpdate(skillId, scope);
    AiSkillDraft draft = draftRepository.findActiveBySkillId(skill.getId())
        .orElseThrow(() -> new IllegalArgumentException(
            "Skill Draft does not exist"
        ));
    AiSkillDraftRevision revision = draftRevisionRepository
        .findActiveByDraftIdAndRevision(draft.getId(), request.revision())
        .filter(candidate -> skill.getId().equals(candidate.getSkillId()))
        .orElseThrow(() -> new IllegalArgumentException(
            "Skill Draft Revision does not exist"
        ));
    assertDebuggableRevision(revision);
    return startDraftRevision(
        skill,
        draft,
        revision,
        scope,
        currentUserId(),
        request.mode(),
        request.testCaseId(),
        request.idempotencyKey(),
        request.input(),
        null,
        null,
        null
    );
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public SkillDraftMockTestRun startDraftMockTestRun(
      final String skillId,
      final SkillDraftMockTestRunStartRequest request
  ) {
    if (request == null || request.revision() == null
        || request.revision() < 1) {
      throw new IllegalArgumentException(
          "Skill Draft Mock test revision must be greater than zero"
      );
    }
    String idempotencyKey = requireIdempotencyKey(request.idempotencyKey());
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiSkillDefinition skill = requireSkillForUpdate(skillId, scope);
    AiSkillDraft draft = draftRepository.findActiveBySkillId(skill.getId())
        .orElseThrow(() -> new IllegalArgumentException(
            "Skill Draft does not exist"
        ));
    AiSkillDraftRevision revision = draftRevisionRepository
        .findActiveByDraftIdAndRevision(draft.getId(), request.revision())
        .filter(candidate -> skill.getId().equals(candidate.getSkillId()))
        .orElseThrow(() -> new IllegalArgumentException(
            "Skill Draft Revision does not exist"
        ));
    assertDebuggableRevision(revision);
    SkillDesignerDocument designer = readDesigner(revision);
    List<TestCase> enabled = designer.tests().stream()
        .filter(TestCase::enabled)
        .toList();
    if (enabled.isEmpty()) {
      throw new IllegalArgumentException(
          "Skill Draft has no enabled Mock test cases"
      );
    }
    String testRunId = "run-" + sha256(
        skill.getId() + ":" + draft.getId() + ":" + revision.getRevision()
            + ":" + scope.scopeType().name() + ":"
            + String.valueOf(scope.tenantId()) + ":" + idempotencyKey
    ).substring(0, 60);
    List<AiSkillExecution> existing = executionRepository
        .findAllActiveByTestRun(
            skill.getId(),
            scope.scopeType(),
            scope.tenantId(),
            testRunId
        );
    if (!existing.isEmpty()) {
      assertTestRunMatches(existing, revision, enabled);
      return summarizeTestRun(testRunId, existing, designer);
    }

    String requestedBy = currentUserId();
    List<AiSkillExecution> executions = new ArrayList<>();
    for (int index = 0; index < enabled.size(); index++) {
      TestCase test = enabled.get(index);
      executions.add(startDraftRevision(
          skill,
          draft,
          revision,
          scope,
          requestedBy,
          SkillDebugMode.MOCK,
          test.id(),
          sha256("test-run:" + testRunId + ":" + test.id()),
          null,
          testRunId,
          index,
          designer
      ));
    }
    return summarizeTestRun(testRunId, executions, designer);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<SkillDraftMockTestRun> findAllDraftMockTestRuns(
      final String skillId,
      final Pageable pageable
  ) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiSkillDefinition skill = requireSkill(skillId, scope);
    return executionRepository.findAllActiveTestRunIdsBySkillAndScope(
        skill.getId(),
        scope.scopeType(),
        scope.tenantId(),
        pageable
    ).map(testRunId -> summarizeTestRun(
        testRunId,
        executionRepository.findAllActiveByTestRun(
            skill.getId(),
            scope.scopeType(),
            scope.tenantId(),
            testRunId
        ),
        null
    ));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<SkillDraftMockTestRun> findDraftMockTestRun(
      final String skillId,
      final String rawTestRunId
  ) {
    String testRunId = required(rawTestRunId, "Mock test Run ID", 64);
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiSkillDefinition skill = requireSkill(skillId, scope);
    List<AiSkillExecution> executions = executionRepository
        .findAllActiveByTestRun(
            skill.getId(),
            scope.scopeType(),
            scope.tenantId(),
            testRunId
        );
    return executions.isEmpty()
        ? Optional.empty()
        : Optional.of(summarizeTestRun(testRunId, executions, null));
  }

  private AiSkillExecution startDraftRevision(
      final AiSkillDefinition skill,
      final AiSkillDraft draft,
      final AiSkillDraftRevision revision,
      final ScopeAssignment scope,
      final String requestedBy,
      final SkillDebugMode debugMode,
      final String testCaseId,
      final String rawIdempotencyKey,
      final Map<String, Object> rawInput,
      final String testRunId,
      final Integer testRunOrder,
      final SkillDesignerDocument pinnedDesigner
  ) {
    MockConfiguration mock = debugMode == SkillDebugMode.MOCK
        ? resolveMockConfiguration(revision, testCaseId, pinnedDesigner) : null;
    if (mock != null && rawInput != null && !rawInput.equals(mock.input())) {
      throw new IllegalArgumentException(
          "MOCK debug input must match the pinned test case input"
      );
    }
    final String idempotencyHash = sha256(
        debugMode.name() + ":" + (mock == null ? "" : mock.testCaseId())
            + ":" + requireIdempotencyKey(rawIdempotencyKey)
    );
    Map<String, Object> input = mock != null
        ? new LinkedHashMap<>(mock.input())
        : rawInput == null ? Map.of() : new LinkedHashMap<>(rawInput);
    String inputJson = writeJson(input);
    assertPayloadSize(inputJson, "Skill Draft debug input");
    Map<String, Object> manifest = readMap(
        revision.getCompiledManifestJson(),
        "Skill Draft compiled Manifest"
    );
    if (!revision.getContentHash().equals(sha256(writeJson(manifest)))) {
      throw new IllegalStateException(
          "Skill Draft Revision content hash does not match its Manifest"
      );
    }
    Map<String, Object> spec = requiredMap(manifest.get("spec"), "Skill spec");
    Map<String, Object> inputSchema = requiredMap(
        spec.get("inputSchema"),
        "Skill input Schema"
    );
    schemaValidator.validate(inputSchema, input, "Skill debug input");
    Optional<AiSkillExecution> existing =
        executionRepository.findActiveDraftByIdempotency(
            skill.getId(),
            draft.getId(),
            revision.getRevision(),
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
      if (!java.util.Objects.equals(testRunId, execution.getTestRunId())
          || !java.util.Objects.equals(
              testRunOrder,
              execution.getTestRunOrder()
          )) {
        throw new IllegalArgumentException(
            "Idempotency key was already used by a different Mock test run"
        );
      }
      return decorate(execution);
    }

    Map<String, Object> workflow = requiredMap(
        spec.get("workflow"),
        "Skill workflow"
    );
    ResolvedBindings bindings = draftCapabilityResolver.resolve(spec);
    WorkflowPlan workflowPlan = workflowPlanCompiler.compile(
        workflow,
        new WorkflowBindings(
            bindings.tools().keySet(),
            bindings.prompts().keySet(),
            bindings.resources().keySet()
        )
    );
    if (mock != null) {
      List<String> missingMocks = workflowPlan.executableSteps().stream()
          .map(ExecutableNode::id)
          .filter(stepId -> !mock.mocks().containsKey(stepId))
          .toList();
      if (!missingMocks.isEmpty()) {
        throw new IllegalArgumentException(
            "MOCK test case has no result for workflow steps: "
                + String.join(", ", missingMocks)
        );
      }
      validateMockToolOutputs(workflowPlan, bindings, mock.mocks());
    }
    SkillExecutionBudget budget = budgetPolicy.normalize(
        spec.get("budgets"),
        workflowPlan.maximumToolCalls()
    );
    SkillExecutionApprovalPolicy approval = approvalPolicy.normalize(
        spec.get("approvals")
    );
    if (debugMode == SkillDebugMode.LIVE
        && bindings.tools().values().stream()
        .anyMatch(ToolBinding::requiresApproval) && !approval.required()) {
      throw new IllegalArgumentException(
          "A non-read-only MCP Tool requires execution approval"
      );
    }
    long initialPayloadBytes = payloadBytes(inputJson);
    if (initialPayloadBytes > budget.maximumPayloadBytes()) {
      throw new IllegalArgumentException(
          "Skill Draft debug input exceeds the payload budget"
      );
    }
    AiSkillExecution execution = new AiSkillExecution();
    execution.setSkillId(skill.getId());
    execution.setSkillVersionId(null);
    execution.setSourceType(SkillExecutionSource.DRAFT);
    execution.setDraftId(draft.getId());
    execution.setDraftRevision(revision.getRevision());
    execution.setDraftContentHash(revision.getContentHash());
    execution.setDebugMode(debugMode);
    execution.setTestCaseId(mock == null ? null : mock.testCaseId());
    execution.setMockConfigHash(mock == null ? null : mock.configHash());
    execution.setMockConfigJson(mock == null ? null : mock.configJson());
    execution.setTestRunId(testRunId);
    execution.setTestRunOrder(testRunOrder);
    execution.setScopeType(scope.scopeType());
    execution.setTenantId(scope.tenantId());
    execution.setIdempotencyKeyHash(idempotencyHash);
    execution.setInputHash(sha256(inputJson));
    execution.setInputJson(inputJson);
    execution.setOutputTemplateJson(workflow.containsKey("output")
        ? writeJson(workflow.get("output")) : null);
    execution.setWorkflowPlanJson(writeJson(workflow));
    execution.setOutputSchemaJson(writeJson(requiredMap(
        spec.get("outputSchema"),
        "Skill output Schema"
    )));
    Instant submittedAt = Instant.now();
    boolean requiresApproval = debugMode == SkillDebugMode.LIVE
        && approval.required();
    execution.setStatus(requiresApproval
        ? SkillExecutionStatus.WAITING_APPROVAL
        : SkillExecutionStatus.PENDING);
    execution.setApprovalRequired(requiresApproval);
    execution.setSelfApprovalAllowed(approval.allowSelfApproval());
    execution.setApprovalInstructions(approval.instructions());
    execution.setApprovalRequestedAt(requiresApproval ? submittedAt : null);
    execution.setPauseRequested(false);
    execution.setInactiveSince(requiresApproval ? submittedAt : null);
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
      AiSkillExecutionStep step = createDraftExecutionStep(
          execution.getId(),
          index,
          workflowSteps.get(index),
          bindings
      );
      step.setStatus(SkillExecutionStepStatus.PENDING);
      step.setAttemptCount(0);
      savedSteps.add(stepRepository.save(step));
    }
    publishCreated(execution, submittedAt);
    return decorate(execution, savedSteps);
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

  @Override
  @Transactional(
      propagation = Propagation.MANDATORY,
      rollbackFor = Exception.class
  )
  public AiSkillExecution startVersionForWorkflow(
      final SkillWorkflowExecutionCommand command
  ) {
    if (command == null || command.executionScope() == null) {
      throw new IllegalArgumentException(
          "Workflow Skill execution command must not be null"
      );
    }
    ScopeAssignment scope = new ScopeAssignment(
        command.executionScope(),
        command.tenantId()
    );
    AiSkillDefinition skill = skillRepository.findActiveByIdForUpdate(
        required(command.skillId(), "Skill ID", 64)
    ).orElseThrow(() -> new IllegalArgumentException(
        "Pinned Skill does not exist"
    ));
    if (!scopeAccessPolicy.canUseResourceFromScope(
        skill.getScopeType(),
        skill.getTenantId(),
        scope.scopeType(),
        scope.tenantId()
    ) || !Boolean.TRUE.equals(skill.getEnabled())) {
      throw new IllegalArgumentException(
          "Pinned Skill is unavailable to the Workflow scope"
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

  @Override
  @Transactional(
      propagation = Propagation.MANDATORY,
      rollbackFor = Exception.class
  )
  public AiSkillExecution cancelPinnedChild(
      final SkillPinnedChildCancelCommand command
  ) {
    if (command == null || command.executionScope() == null) {
      throw new IllegalArgumentException(
          "Pinned Skill cancellation command must not be null"
      );
    }
    String skillId = required(command.skillId(), "Skill ID", 64);
    String skillVersionId = required(
        command.skillVersionId(),
        "Skill version ID",
        64
    );
    String executionId = required(
        command.executionId(),
        "Skill execution ID",
        64
    );
    String idempotencyHash = sha256(requireIdempotencyKey(
        command.idempotencyKey()
    ));
    String actor = required(
        command.actorId(),
        "Skill cancellation actor ID",
        64
    );
    String reason = optionalComment(command.reason());
    if (reason == null) {
      throw new IllegalArgumentException(
          "Skill cancellation reason must not be blank"
      );
    }
    AiSkillExecution execution = executionRepository
        .findActiveByIdForUpdate(executionId)
        .orElseThrow(() -> new IllegalArgumentException(
            "Pinned Skill execution does not exist"
        ));
    if (execution.getSourceType() != SkillExecutionSource.PUBLISHED
        || !skillId.equals(execution.getSkillId())
        || !skillVersionId.equals(execution.getSkillVersionId())
        || command.executionScope() != execution.getScopeType()
        || !java.util.Objects.equals(
            command.tenantId(),
            execution.getTenantId()
        )
        || !idempotencyHash.equals(execution.getIdempotencyKeyHash())) {
      throw new IllegalArgumentException(
          "Pinned Skill execution does not match cancellation command"
      );
    }
    return cancelExecution(execution, actor, reason, true);
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
    execution.setSourceType(SkillExecutionSource.PUBLISHED);
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
    publishCreated(execution, submittedAt);
    return decorate(execution, savedSteps);
  }

  private void publishCreated(
      final AiSkillExecution execution,
      final Instant occurredAt
  ) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("sourceType", execution.getSourceType().name());
    if (execution.getDraftRevision() != null) {
      payload.put("draftRevision", execution.getDraftRevision());
      payload.put("debugMode", execution.getDebugMode().name());
    }
    eventPublisher.publish(
        execution,
        SkillExecutionEventType.EXECUTION_CREATED,
        null,
        execution.getRequestedBy(),
        payload,
        occurredAt
    );
    if (Boolean.TRUE.equals(execution.getApprovalRequired())) {
      eventPublisher.publish(
          execution,
          SkillExecutionEventType.APPROVAL_REQUIRED,
          null,
          execution.getRequestedBy(),
          Map.of(),
          occurredAt
      );
    }
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

  private static String draftBindingReference(
      final String type,
      final String alias
  ) {
    return "draft-" + sha256(type + ":" + alias).substring(0, 32);
  }

  private AiSkillExecutionStep createDraftExecutionStep(
      final String executionId,
      final int order,
      final ExecutableNode source,
      final ResolvedBindings bindings
  ) {
    AiSkillExecutionStep step = new AiSkillExecutionStep();
    step.setExecutionId(executionId);
    step.setStepId(source.id());
    step.setStepOrder(order);
    switch (source) {
      case ToolNode tool -> {
        ToolBinding binding = bindings.tools().get(tool.toolAlias());
        if (binding == null) {
          throw new IllegalStateException(
              "Workflow Tool binding does not exist: " + tool.toolAlias()
          );
        }
        step.setStepType("tool");
        step.setBindingId(draftBindingReference("tool", binding.alias()));
        step.setCapabilityAlias(binding.alias());
        step.setMcpServerId(binding.serverId());
        step.setCapabilitySnapshotId(binding.snapshotId());
        step.setCapabilityName(binding.name());
        step.setCapabilitySchemaHash(binding.schemaHash());
        step.setCapabilityTemplate(false);
        step.setInputTemplateJson(tool.argumentsTemplate() == null
            ? null : writeJson(tool.argumentsTemplate()));
      }
      case PromptNode prompt -> {
        PromptBinding binding = bindings.prompts().get(prompt.promptAlias());
        if (binding == null) {
          throw new IllegalStateException(
              "Workflow Prompt binding does not exist: "
                  + prompt.promptAlias()
          );
        }
        step.setStepType("prompt");
        step.setBindingId(draftBindingReference("prompt", binding.alias()));
        step.setCapabilityAlias(binding.alias());
        step.setMcpServerId(binding.serverId());
        step.setCapabilitySnapshotId(binding.snapshotId());
        step.setCapabilityName(binding.name());
        step.setCapabilitySchemaHash(binding.schemaHash());
        step.setCapabilityTemplate(false);
        step.setInputTemplateJson(prompt.argumentsTemplate() == null
            ? null : writeJson(prompt.argumentsTemplate()));
      }
      case ResourceNode resource -> {
        ResourceBinding binding = bindings.resources().get(
            resource.resourceAlias()
        );
        if (binding == null) {
          throw new IllegalStateException(
              "Workflow Resource binding does not exist: "
                  + resource.resourceAlias()
          );
        }
        if (binding.template() && resource.uriTemplate() == null) {
          throw new IllegalArgumentException(
              "Resource Template workflow step must define uri"
          );
        }
        step.setStepType("resource");
        step.setBindingId(draftBindingReference("resource", binding.alias()));
        step.setCapabilityAlias(binding.alias());
        step.setMcpServerId(binding.serverId());
        step.setCapabilitySnapshotId(binding.snapshotId());
        step.setCapabilityName(binding.selector());
        step.setCapabilitySchemaHash(binding.schemaHash());
        step.setCapabilityTemplate(binding.template());
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
  public Page<AiSkillExecution> findAllDraftDebug(
      final String skillId,
      final Pageable pageable
  ) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiSkillDefinition skill = requireSkill(skillId, scope);
    return executionRepository.findAllActiveBySkillScopeAndSource(
        skill.getId(),
        scope.scopeType(),
        scope.tenantId(),
        SkillExecutionSource.DRAFT,
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
  @Transactional(readOnly = true)
  public Optional<AiSkillExecution> findDraftDebug(
      final String skillId,
      final String executionId
  ) {
    return find(skillId, executionId)
        .filter(execution ->
            execution.getSourceType() == SkillExecutionSource.DRAFT);
  }

  private MockConfiguration resolveMockConfiguration(
      final AiSkillDraftRevision revision,
      final String rawTestCaseId,
      final SkillDesignerDocument pinnedDesigner
  ) {
    String testCaseId = required(rawTestCaseId, "Mock test case ID", 64);
    SkillDesignerDocument designer = pinnedDesigner == null
        ? readDesigner(revision) : pinnedDesigner;
    TestCase test = designer.tests().stream()
        .filter(candidate -> testCaseId.equals(candidate.id()))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException(
            "Mock test case does not exist in the pinned Draft Revision"
        ));
    if (!test.enabled()) {
      throw new IllegalArgumentException("Mock test case is disabled");
    }
    Map<String, NodeMock> mocks = new LinkedHashMap<>();
    for (NodeMock mock : test.mocks()) {
      mocks.put(mock.nodeId(), mock);
    }
    Map<String, Object> persisted = new LinkedHashMap<>();
    persisted.put("testCaseId", testCaseId);
    persisted.put("mocks", mocks);
    persisted.put("assertions", test.assertions());
    String configJson = writeJson(persisted);
    assertPayloadSize(configJson, "Skill Draft mock configuration");
    return new MockConfiguration(
        testCaseId,
        test.input(),
        Map.copyOf(mocks),
        configJson,
        sha256(configJson)
    );
  }

  private SkillDesignerDocument readDesigner(
      final AiSkillDraftRevision revision
  ) {
    try {
      return objectMapper.readValue(
          revision.getDesignerJson(),
          SkillDesignerDocument.class
      );
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(
          "Stored Skill Designer test cases are invalid",
          exception
      );
    }
  }

  private static void assertDebuggableRevision(
      final AiSkillDraftRevision revision
  ) {
    if (revision.getValidationStatus() != SkillDraftValidationStatus.VALID
        || revision.getCompiledManifestJson() == null
        || revision.getContentHash() == null) {
      throw new IllegalStateException(
          "Skill Draft Revision is not valid and cannot be debugged"
      );
    }
  }

  private void assertTestRunMatches(
      final List<AiSkillExecution> executions,
      final AiSkillDraftRevision revision,
      final List<TestCase> enabled
  ) {
    if (executions.size() != enabled.size()) {
      throw new IllegalStateException(
          "Persisted Mock test run has an incomplete case set"
      );
    }
    for (int index = 0; index < enabled.size(); index++) {
      AiSkillExecution execution = executions.get(index);
      if (!enabled.get(index).id().equals(execution.getTestCaseId())
          || !Integer.valueOf(index).equals(execution.getTestRunOrder())
          || execution.getDebugMode() != SkillDebugMode.MOCK
          || !Long.valueOf(revision.getRevision()).equals(
              execution.getDraftRevision()
          )
          || !revision.getContentHash().equals(
              execution.getDraftContentHash()
          )) {
        throw new IllegalStateException(
            "Persisted Mock test run does not match its Draft Revision"
        );
      }
    }
  }

  private SkillDraftMockTestRun summarizeTestRun(
      final String testRunId,
      final List<AiSkillExecution> executions,
      final SkillDesignerDocument knownDesigner
  ) {
    if (executions.isEmpty()) {
      throw new IllegalStateException("Persisted Mock test run is empty");
    }
    AiSkillExecution first = executions.getFirst();
    SkillDesignerDocument designer = knownDesigner;
    AiSkillDraftRevision revision = draftRevisionRepository
        .findActiveByDraftIdAndRevision(
            first.getDraftId(),
            first.getDraftRevision()
        )
        .orElseThrow(() -> new IllegalStateException(
            "Mock test run Draft Revision no longer exists"
        ));
    if (designer == null) {
      designer = readDesigner(revision);
    }
    List<TestCase> enabled = designer.tests().stream()
        .filter(TestCase::enabled)
        .toList();
    assertTestRunMatches(executions, revision, enabled);
    Map<String, String> names = new HashMap<>();
    enabled.forEach(test -> names.put(test.id(), test.name()));
    List<SkillDraftMockTestRunCase> cases = executions.stream()
        .map(execution -> new SkillDraftMockTestRunCase(
            execution.getId(),
            execution.getTestCaseId(),
            names.getOrDefault(
                execution.getTestCaseId(),
                execution.getTestCaseId()
            ),
            execution.getTestRunOrder(),
            execution.getStatus(),
            execution.getAssertionsPassed(),
            execution.getErrorCode(),
            execution.getErrorMessage(),
            execution.getStartedAt(),
            execution.getCompletedAt()
        ))
        .toList();
    int passed = (int) executions.stream()
        .filter(execution ->
            execution.getStatus() == SkillExecutionStatus.SUCCEEDED)
        .count();
    int failed = (int) executions.stream()
        .filter(execution -> isTerminal(execution.getStatus()))
        .filter(execution ->
            execution.getStatus() != SkillExecutionStatus.SUCCEEDED)
        .count();
    int completed = passed + failed;
    SkillDraftMockTestRunStatus status = completed < executions.size()
        ? SkillDraftMockTestRunStatus.RUNNING
        : failed > 0
            ? SkillDraftMockTestRunStatus.FAILED
            : SkillDraftMockTestRunStatus.PASSED;
    Instant createdAt = executions.stream()
        .map(AiSkillExecution::getCreatedAt)
        .filter(java.util.Objects::nonNull)
        .min(Instant::compareTo)
        .orElse(null);
    Instant completedAt = completed == executions.size()
        ? executions.stream()
            .map(AiSkillExecution::getCompletedAt)
            .filter(java.util.Objects::nonNull)
            .max(Instant::compareTo)
            .orElse(null)
        : null;
    return new SkillDraftMockTestRun(
        testRunId,
        first.getSkillId(),
        first.getDraftId(),
        first.getDraftRevision(),
        first.getDraftContentHash(),
        status,
        executions.size(),
        completed,
        passed,
        failed,
        createdAt,
        completedAt,
        cases
    );
  }

  void validateMockToolOutputs(
      final WorkflowPlan workflowPlan,
      final ResolvedBindings bindings,
      final Map<String, NodeMock> mocks
  ) {
    for (ExecutableNode step : workflowPlan.executableSteps()) {
      if (!(step instanceof ToolNode tool)) {
        continue;
      }
      NodeMock nodeMock = mocks.get(step.id());
      ToolBinding binding = bindings.tools().get(tool.toolAlias());
      if (nodeMock == null || nodeMock.mode() != MockMode.SUCCESS
          || binding == null || binding.outputSchema() == null
          || !schemaValidator.supports(binding.outputSchema())) {
        continue;
      }
      schemaValidator.validate(
          binding.outputSchema(),
          nodeMock.output(),
          "MOCK output for workflow step " + step.id()
      );
    }
  }

  @Override
  @Transactional(readOnly = true)
  public SkillExecutionEventFeed findDraftDebugEvents(
      final String skillId,
      final String executionId,
      final long afterSequence,
      final int limit
  ) {
    if (afterSequence < 0) {
      throw new IllegalArgumentException(
          "Skill event cursor must be zero or greater"
      );
    }
    int boundedLimit = Math.max(1, Math.min(limit, 200));
    AiSkillExecution execution = findDraftDebug(skillId, executionId)
        .orElseThrow(() -> new IllegalArgumentException(
            "Skill Draft debug execution does not exist"
        ));
    List<AiSkillExecutionEvent> fetched =
        eventRepository.findActiveAfterSequence(
            execution.getId(),
            afterSequence,
            PageRequest.of(0, boundedLimit + 1)
        );
    boolean hasMore = fetched.size() > boundedLimit;
    List<AiSkillExecutionEvent> events = fetched.stream()
        .limit(boundedLimit)
        .map(eventPublisher::decorate)
        .toList();
    long nextSequence = events.isEmpty()
        ? afterSequence : events.getLast().getSequence();
    return new SkillExecutionEventFeed(
        afterSequence,
        nextSequence,
        hasMore,
        execution.getStatus(),
        events
    );
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
    eventPublisher.publish(
        execution,
        SkillExecutionEventType.APPROVAL_GRANTED,
        null,
        actor,
        Map.of(),
        now
    );
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
    eventPublisher.publish(
        execution,
        SkillExecutionEventType.APPROVAL_REJECTED,
        null,
        actor,
        Map.of(),
        now
    );
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
    eventPublisher.publish(
        execution,
        execution.getStatus() == SkillExecutionStatus.PAUSED
            ? SkillExecutionEventType.EXECUTION_PAUSED
            : SkillExecutionEventType.PAUSE_REQUESTED,
        execution.getCurrentStepId(),
        execution.getPauseRequestedBy(),
        Map.of(),
        now
    );
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
    execution.setBreakpointBypassedStepId(execution.getCurrentStepId());
    execution.setResumedAt(now);
    execution.setResumedBy(currentUserId());
    if (Boolean.TRUE.equals(execution.getApprovalRequired())
        && execution.getApprovedAt() == null) {
      execution.setStatus(SkillExecutionStatus.WAITING_APPROVAL);
    } else {
      activate(execution, now);
    }
    executionRepository.save(execution);
    eventPublisher.publish(
        execution,
        SkillExecutionEventType.EXECUTION_RESUMED,
        execution.getCurrentStepId(),
        execution.getResumedBy(),
        Map.of(),
        now
    );
    return decorate(execution);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiSkillExecution cancel(
      final String skillId,
      final String executionId,
      final SkillExecutionCancelRequest request
  ) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiSkillDefinition skill = requireSkill(skillId, scope);
    AiSkillExecution execution = requireExecutionForUpdate(
        skill,
        scope,
        executionId
    );
    String actor = currentUserId();
    return cancelExecution(
        execution,
        actor,
        optionalComment(request == null ? null : request.reason()),
        false
    );
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiSkillExecution setDraftDebugBreakpoints(
      final String skillId,
      final String executionId,
      final SkillExecutionBreakpointsRequest request
  ) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiSkillDefinition skill = requireSkill(skillId, scope);
    AiSkillExecution execution = requireExecutionForUpdate(
        skill,
        scope,
        executionId
    );
    if (execution.getSourceType() != SkillExecutionSource.DRAFT) {
      throw new IllegalArgumentException(
          "Skill Draft debug execution does not exist"
      );
    }
    if (isTerminal(execution.getStatus())) {
      throw new IllegalStateException(
          "Terminal Skill execution breakpoints cannot be changed"
      );
    }
    if (request == null || request.stepIds().size() > 64) {
      throw new IllegalArgumentException(
          "Skill debug breakpoints must contain at most 64 steps"
      );
    }
    List<String> stepIds = request.stepIds().stream()
        .map(stepId -> required(stepId, "Breakpoint step ID", 64))
        .distinct()
        .toList();
    List<AiSkillExecutionStep> steps =
        stepRepository.findAllActiveByExecutionId(execution.getId());
    java.util.Set<String> available = steps.stream()
        .map(AiSkillExecutionStep::getStepId)
        .collect(java.util.stream.Collectors.toSet());
    if (!available.containsAll(stepIds)) {
      throw new IllegalArgumentException(
          "Skill debug breakpoint references an unknown workflow step"
      );
    }
    execution.setBreakpointStepIdsJson(writeJson(stepIds));
    execution.setBreakpointStepIds(stepIds);
    execution.setBreakpointBypassedStepId(null);
    executionRepository.save(execution);
    eventPublisher.publish(
        execution,
        SkillExecutionEventType.BREAKPOINTS_UPDATED,
        execution.getCurrentStepId(),
        currentUserId(),
        Map.of("count", stepIds.size()),
        Instant.now()
    );
    return decorate(execution, steps);
  }

  private void cancelImmediately(
      final AiSkillExecution execution,
      final String actor,
      final Instant now
  ) {
    List<AiSkillExecutionStep> steps =
        stepRepository.findAllActiveByExecutionId(execution.getId());
    for (AiSkillExecutionStep step : steps) {
      if (step.getStatus() == SkillExecutionStepStatus.PENDING
          || step.getStatus() == SkillExecutionStepStatus.RUNNING) {
        step.setStatus(SkillExecutionStepStatus.SKIPPED);
        step.setCompletedAt(now);
        step.setErrorCode("SKILL_EXECUTION_CANCELLED");
        step.setErrorMessage("Execution cancelled before the step completed");
        stepRepository.save(step);
      }
    }
    execution.setStatus(SkillExecutionStatus.CANCELLED);
    execution.setCompletedAt(now);
    execution.setPauseRequested(false);
    execution.setInactiveSince(null);
    clearLease(execution);
    executionRepository.save(execution);
    eventPublisher.publish(
        execution,
        SkillExecutionEventType.EXECUTION_CANCELLED,
        execution.getCurrentStepId(),
        actor,
        Map.of(),
        now
    );
  }

  private AiSkillExecution cancelExecution(
      final AiSkillExecution execution,
      final String actor,
      final String reason,
      final boolean allTerminalStatusesAreIdempotent
  ) {
    if (execution.getStatus() == SkillExecutionStatus.CANCELLED) {
      return decorate(execution);
    }
    if (isTerminal(execution.getStatus())) {
      if (allTerminalStatusesAreIdempotent) {
        return decorate(execution);
      }
      throw new IllegalStateException(
          "Terminal Skill execution cannot be cancelled"
      );
    }
    Instant now = Instant.now();
    if (execution.getStatus() == SkillExecutionStatus.RUNNING) {
      if (Boolean.TRUE.equals(execution.getCancelRequested())) {
        return decorate(execution);
      }
      markCancellationRequested(execution, actor, reason, now);
      executionRepository.save(execution);
      eventPublisher.publish(
          execution,
          SkillExecutionEventType.CANCEL_REQUESTED,
          execution.getCurrentStepId(),
          actor,
          Map.of(),
          now
      );
    } else {
      if (!Boolean.TRUE.equals(execution.getCancelRequested())) {
        markCancellationRequested(execution, actor, reason, now);
      }
      cancelImmediately(execution, actor, now);
    }
    return decorate(execution);
  }

  private static void markCancellationRequested(
      final AiSkillExecution execution,
      final String actor,
      final String reason,
      final Instant now
  ) {
    execution.setCancelRequested(true);
    execution.setCancelRequestedAt(now);
    execution.setCancelRequestedBy(actor);
    execution.setCancelReason(reason);
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
    execution.setAssertionResults(execution.getAssertionResultsJson() == null
        ? null : readAssertionResults(execution.getAssertionResultsJson()));
    execution.setBreakpointStepIds(
        readBreakpointStepIds(execution.getBreakpointStepIdsJson())
    );
    steps.forEach(step -> {
      step.setInputTemplate(step.getInputTemplateJson() == null
          ? null : readValue(
              step.getInputTemplateJson(),
              "Skill step input template"
          ));
      step.setInput(step.getInputJson() == null
          ? null : readMap(step.getInputJson(), "Skill step input"));
      step.setOutput(step.getOutputJson() == null
          ? null : readValue(step.getOutputJson(), "Skill step output"));
    });
    execution.setSteps(List.copyOf(steps));
    return execution;
  }

  private List<SkillTestAssertionResult> readAssertionResults(
      final String json
  ) {
    try {
      return List.copyOf(objectMapper.readValue(json, ASSERTION_RESULTS_TYPE));
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(
          "Skill test assertion results are corrupted",
          ex
      );
    }
  }

  private List<String> readBreakpointStepIds(final String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return List.copyOf(objectMapper.readValue(json, STRING_LIST_TYPE));
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(
          "Skill debug breakpoints are corrupted",
          ex
      );
    }
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

  private static Map<String, Object> requiredMap(
      final Object value,
      final String label
  ) {
    if (!(value instanceof Map<?, ?> source)) {
      throw new IllegalArgumentException(label + " must be an object");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    source.forEach((key, nested) -> result.put(String.valueOf(key), nested));
    return result;
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

  private record MockConfiguration(
      String testCaseId,
      Map<String, Object> input,
      Map<String, NodeMock> mocks,
      String configJson,
      String configHash
  ) {
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
