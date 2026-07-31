package org.simplepoint.plugin.ai.workflow.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.simplepoint.plugin.ai.skill.service.support.SkillJsonSchemaValidator;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowDefinition;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowDependencyBinding;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowExecution;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowExecutionEvent;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowHumanTask;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowNodeExecution;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowVersion;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowDependencyType;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionEventFeed;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionEventType;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionPauseRequest;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionStartRequest;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionStatus;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowHumanTaskResponseRequest;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowHumanTaskStatus;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowNodeExecutionStatus;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowStatus;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowVersionStatus;
import org.simplepoint.plugin.ai.workflow.api.properties.WorkflowExecutionProperties;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowDefinitionRepository;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowDependencyBindingRepository;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowExecutionEventRepository;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowExecutionRepository;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowHumanTaskRepository;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowNodeExecutionRepository;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowVersionRepository;
import org.simplepoint.plugin.ai.workflow.api.service.AiWorkflowExecutionService;
import org.simplepoint.plugin.ai.workflow.service.execution.AiWorkflowExecutionEventPublisher;
import org.simplepoint.plugin.ai.workflow.service.support.WorkflowManifestCompiler;
import org.simplepoint.plugin.ai.workflow.service.support.WorkflowManifestCompiler.CompiledWorkflow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scope-aware durable Workflow execution control plane.
 */
@Service
public class AiWorkflowExecutionServiceImpl
    implements AiWorkflowExecutionService {

  private static final TypeReference<Map<String, Object>> MAP_TYPE =
      new TypeReference<>() {
      };

  private static final int MAXIMUM_EVENT_PAGE = 500;

  private final AiWorkflowDefinitionRepository workflowRepository;

  private final AiWorkflowVersionRepository versionRepository;

  private final AiWorkflowDependencyBindingRepository bindingRepository;

  private final AiWorkflowExecutionRepository executionRepository;

  private final AiWorkflowNodeExecutionRepository nodeRepository;

  private final AiWorkflowHumanTaskRepository taskRepository;

  private final AiWorkflowExecutionEventRepository eventRepository;

  private final AiWorkflowExecutionEventPublisher eventPublisher;

  private final AiScopeAccessPolicy scopeAccessPolicy;

  private final SkillJsonSchemaValidator schemaValidator;

  private final WorkflowManifestCompiler manifestCompiler;

  private final WorkflowExecutionProperties properties;

  private final ObjectMapper objectMapper;

  private final ObjectMapper canonicalMapper;

  /**
   * Creates the durable Workflow control plane.
   */
  public AiWorkflowExecutionServiceImpl(
      final AiWorkflowDefinitionRepository workflowRepository,
      final AiWorkflowVersionRepository versionRepository,
      final AiWorkflowDependencyBindingRepository bindingRepository,
      final AiWorkflowExecutionRepository executionRepository,
      final AiWorkflowNodeExecutionRepository nodeRepository,
      final AiWorkflowHumanTaskRepository taskRepository,
      final AiWorkflowExecutionEventRepository eventRepository,
      final AiWorkflowExecutionEventPublisher eventPublisher,
      final AiScopeAccessPolicy scopeAccessPolicy,
      final SkillJsonSchemaValidator schemaValidator,
      final WorkflowManifestCompiler manifestCompiler,
      final WorkflowExecutionProperties properties,
      final ObjectMapper objectMapper
  ) {
    this.workflowRepository = workflowRepository;
    this.versionRepository = versionRepository;
    this.bindingRepository = bindingRepository;
    this.executionRepository = executionRepository;
    this.nodeRepository = nodeRepository;
    this.taskRepository = taskRepository;
    this.eventRepository = eventRepository;
    this.eventPublisher = eventPublisher;
    this.scopeAccessPolicy = scopeAccessPolicy;
    this.schemaValidator = schemaValidator;
    this.manifestCompiler = manifestCompiler;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.canonicalMapper = objectMapper.copy()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiWorkflowExecution start(
      final String workflowId,
      final WorkflowExecutionStartRequest request
  ) {
    if (request == null) {
      throw new IllegalArgumentException(
          "Workflow execution request must not be null"
      );
    }
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiWorkflowDefinition workflow =
        requireWorkflow(workflowId, scope, true);
    if (!Boolean.TRUE.equals(workflow.getEnabled())
        || workflow.getStatus() != WorkflowStatus.ACTIVE
        || workflow.getActiveVersionId() == null) {
      throw new IllegalStateException(
          "Workflow has no active published version"
      );
    }
    AiWorkflowVersion version = versionRepository.findActiveById(
        workflow.getActiveVersionId()
    ).filter(candidate ->
        workflow.getId().equals(candidate.getWorkflowId()))
        .orElseThrow(() -> new IllegalStateException(
            "Active Workflow version does not exist"
        ));
    if (version.getStatus() != WorkflowVersionStatus.PUBLISHED) {
      throw new IllegalStateException(
          "Active Workflow version is not published"
      );
    }
    String idempotencyHash = sha256(required(
        request.idempotencyKey(),
        "Workflow execution idempotency key",
        128
    ));
    Map<String, Object> input = request.input() == null
        ? Map.of() : new LinkedHashMap<>(request.input());
    String inputJson = write(input);
    assertPayloadSize(inputJson, "Workflow execution input");
    Map<String, Object> manifest = readMap(
        version.getManifestJson(),
        "Workflow manifest"
    );
    Map<String, Object> spec = map(
        manifest.get("spec"),
        "Workflow spec"
    );
    schemaValidator.validate(
        map(spec.get("inputSchema"), "Workflow input schema"),
        input,
        "Workflow input"
    );
    Optional<AiWorkflowExecution> existing =
        executionRepository.findActiveByIdempotency(
            workflow.getId(),
            scope.scopeType(),
            scope.tenantId(),
            idempotencyHash
        );
    if (existing.isPresent()) {
      AiWorkflowExecution execution = existing.orElseThrow();
      if (!sha256(inputJson).equals(execution.getInputHash())) {
        throw new IllegalArgumentException(
            "Idempotency key was already used with different input"
        );
      }
      return decorate(execution);
    }
    CompiledWorkflow compiled = manifestCompiler.compile(manifest);
    List<AiWorkflowDependencyBinding> bindings =
        bindingRepository.findAllActiveByWorkflowVersionId(version.getId());
    assertDependencySnapshot(compiled, bindings);
    Instant now = Instant.now();
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    AiWorkflowExecution execution = new AiWorkflowExecution();
    execution.setWorkflowId(workflow.getId());
    execution.setWorkflowVersionId(version.getId());
    execution.setWorkflowVersionContentHash(version.getContentHash());
    execution.setScopeType(scope.scopeType());
    execution.setTenantId(scope.tenantId());
    execution.setRequestedBy(context == null ? null : context.getUserId());
    execution.setRequestContextId(
        context == null ? null : context.getContextId()
    );
    execution.setIdempotencyKeyHash(idempotencyHash);
    execution.setInputHash(sha256(inputJson));
    execution.setInputJson(inputJson);
    execution.setPlanJson(write(planSnapshot(manifest, bindings)));
    execution.setStatus(WorkflowExecutionStatus.PENDING);
    execution.setMaximumDurationSeconds(
        compiled.budgets().maximumDurationSeconds()
    );
    execution.setMaximumNodeExecutions(
        compiled.budgets().maximumNodeExecutions()
    );
    execution.setMaximumParallelism(
        compiled.budgets().maximumParallelism()
    );
    execution.setConsumedNodeExecutions(0);
    execution.setDeadlineAt(
        now.plusSeconds(compiled.budgets().maximumDurationSeconds())
    );
    execution.setPauseRequested(false);
    execution.setLeaseToken(0L);
    execution.setAttemptCount(0);
    execution = executionRepository.save(execution);
    saveNodeCheckpoints(execution, compiled, bindings);
    eventPublisher.publish(
        execution,
        WorkflowExecutionEventType.EXECUTION_CREATED,
        null,
        null,
        execution.getRequestedBy(),
        Map.of("workflowVersionId", version.getId()),
        now
    );
    return decorate(execution);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AiWorkflowExecution> findAll(
      final String workflowId,
      final Pageable pageable
  ) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiWorkflowDefinition workflow =
        requireWorkflow(workflowId, scope, false);
    return executionRepository.findAllActiveByWorkflowAndScope(
        workflow.getId(),
        scope.scopeType(),
        scope.tenantId(),
        pageable
    ).map(this::decorateSummary);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AiWorkflowExecution> find(
      final String workflowId,
      final String executionId
  ) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiWorkflowDefinition workflow =
        requireWorkflow(workflowId, scope, false);
    return executionRepository.findActiveById(required(
        executionId,
        "Workflow execution ID",
        64
    )).filter(execution ->
        workflow.getId().equals(execution.getWorkflowId()))
        .filter(execution -> execution.getScopeType() == scope.scopeType())
        .filter(execution -> Objects.equals(
            execution.getTenantId(),
            scope.tenantId()
        ))
        .map(this::decorate);
  }

  @Override
  @Transactional(readOnly = true)
  public WorkflowExecutionEventFeed findEvents(
      final String workflowId,
      final String executionId,
      final long afterSequence,
      final int limit
  ) {
    if (afterSequence < -1) {
      throw new IllegalArgumentException(
          "Workflow event cursor must be at least -1"
      );
    }
    int boundedLimit = Math.max(1, Math.min(limit, MAXIMUM_EVENT_PAGE));
    AiWorkflowExecution execution =
        requireVisibleExecution(workflowId, executionId, false);
    List<AiWorkflowExecutionEvent> page =
        eventRepository.findAfterSequence(
            execution.getId(),
            afterSequence,
            PageRequest.of(0, boundedLimit + 1)
        );
    boolean hasMore = page.size() > boundedLimit;
    List<AiWorkflowExecutionEvent> events = page.stream()
        .limit(boundedLimit)
        .map(eventPublisher::decorate)
        .toList();
    long next = events.isEmpty()
        ? afterSequence : events.get(events.size() - 1).getSequence();
    return new WorkflowExecutionEventFeed(
        afterSequence,
        next,
        hasMore,
        execution.getStatus(),
        events
    );
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiWorkflowExecution pause(
      final String workflowId,
      final String executionId,
      final WorkflowExecutionPauseRequest request
  ) {
    AiWorkflowExecution execution =
        requireVisibleExecution(workflowId, executionId, true);
    assertNotTerminal(execution);
    if (execution.getStatus() == WorkflowExecutionStatus.PAUSED) {
      return decorate(execution);
    }
    Instant now = Instant.now();
    execution.setPauseRequested(true);
    execution.setPauseRequestedAt(now);
    execution.setPauseRequestedBy(currentUserId());
    execution.setPauseReason(optional(
        request == null ? null : request.reason(),
        512
    ));
    if (safeToPause(execution.getStatus())) {
      execution.setStatus(WorkflowExecutionStatus.PAUSED);
      execution.setPausedAt(now);
      execution.setInactiveSince(now);
      clearLease(execution);
      eventPublisher.publish(
          execution,
          WorkflowExecutionEventType.EXECUTION_PAUSED,
          null,
          null,
          currentUserId(),
          Map.of(),
          now
      );
    } else {
      eventPublisher.publish(
          execution,
          WorkflowExecutionEventType.EXECUTION_PAUSE_REQUESTED,
          null,
          null,
          currentUserId(),
          Map.of(),
          now
      );
    }
    return decorate(executionRepository.save(execution));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiWorkflowExecution resume(
      final String workflowId,
      final String executionId
  ) {
    AiWorkflowExecution execution =
        requireVisibleExecution(workflowId, executionId, true);
    if (execution.getStatus() != WorkflowExecutionStatus.PAUSED) {
      throw new IllegalStateException(
          "Workflow execution is not paused"
      );
    }
    Instant now = Instant.now();
    execution.setStatus(WorkflowExecutionStatus.PENDING);
    execution.setPauseRequested(false);
    execution.setInactiveSince(null);
    execution.setResumedAt(now);
    execution.setResumedBy(currentUserId());
    execution.setNextPollAt(now);
    eventPublisher.publish(
        execution,
        WorkflowExecutionEventType.EXECUTION_RESUMED,
        null,
        null,
        currentUserId(),
        Map.of(),
        now
    );
    return decorate(executionRepository.save(execution));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiWorkflowExecution respondHumanTask(
      final String workflowId,
      final String executionId,
      final String taskId,
      final WorkflowHumanTaskResponseRequest request
  ) {
    AiWorkflowExecution execution =
        requireVisibleExecution(workflowId, executionId, true);
    if (execution.getStatus() != WorkflowExecutionStatus.WAITING_HUMAN
        && execution.getStatus() != WorkflowExecutionStatus.PAUSED) {
      throw new IllegalStateException(
          "Workflow execution is not waiting for human input"
      );
    }
    AiWorkflowHumanTask task =
        taskRepository.findActiveByIdAndExecutionIdForUpdate(
            required(taskId, "Workflow human task ID", 64),
            execution.getId()
        ).orElseThrow(() -> new IllegalArgumentException(
            "Workflow human task does not exist"
        ));
    if (task.getStatus() != WorkflowHumanTaskStatus.OPEN) {
      throw new IllegalStateException(
          "Workflow human task is not open"
      );
    }
    Map<String, Object> output = request == null
        || request.output() == null
        ? Map.of() : new LinkedHashMap<>(request.output());
    schemaValidator.validate(
        readMap(task.getInputSchemaJson(), "Human task response schema"),
        output,
        "Human task response"
    );
    String outputJson = write(output);
    assertPayloadSize(outputJson, "Human task response");
    Instant now = Instant.now();
    task.setOutputJson(outputJson);
    task.setOutputHash(sha256(outputJson));
    task.setStatus(WorkflowHumanTaskStatus.COMPLETED);
    task.setResolvedAt(now);
    task.setResolvedBy(currentUserId());
    taskRepository.save(task);
    AiWorkflowNodeExecution node = nodeRepository
        .findActiveByExecutionIdAndNodeIdForUpdate(
            execution.getId(),
            task.getNodeId()
        ).orElseThrow(() -> new IllegalStateException(
            "Workflow human node checkpoint does not exist"
        ));
    node.setStatus(WorkflowNodeExecutionStatus.SUCCEEDED);
    node.setOutputJson(outputJson);
    node.setOutputHash(task.getOutputHash());
    node.setCompletedAt(now);
    nodeRepository.save(node);
    if (execution.getStatus() != WorkflowExecutionStatus.PAUSED) {
      execution.setStatus(WorkflowExecutionStatus.PENDING);
      execution.setNextPollAt(now);
      execution.setInactiveSince(null);
    }
    eventPublisher.publish(
        execution,
        WorkflowExecutionEventType.HUMAN_TASK_COMPLETED,
        node.getId(),
        task.getId(),
        currentUserId(),
        Map.of("nodeId", node.getNodeId()),
        now
    );
    return decorate(executionRepository.save(execution));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiWorkflowExecution cancel(
      final String workflowId,
      final String executionId
  ) {
    AiWorkflowExecution execution =
        requireVisibleExecution(workflowId, executionId, true);
    assertNotTerminal(execution);
    Instant now = Instant.now();
    execution.setStatus(WorkflowExecutionStatus.CANCELLED);
    execution.setCompletedAt(now);
    execution.setInactiveSince(null);
    clearLease(execution);
    for (AiWorkflowHumanTask task
        : taskRepository.findAllActiveByExecutionId(execution.getId())) {
      if (task.getStatus() == WorkflowHumanTaskStatus.OPEN) {
        task.setStatus(WorkflowHumanTaskStatus.CANCELLED);
        task.setResolvedAt(now);
        task.setResolvedBy(currentUserId());
        taskRepository.save(task);
      }
    }
    eventPublisher.publish(
        execution,
        WorkflowExecutionEventType.EXECUTION_CANCELLED,
        null,
        null,
        currentUserId(),
        Map.of(),
        now
    );
    return decorate(executionRepository.save(execution));
  }

  private void saveNodeCheckpoints(
      final AiWorkflowExecution execution,
      final CompiledWorkflow compiled,
      final List<AiWorkflowDependencyBinding> bindings
  ) {
    Map<String, AiWorkflowDependencyBinding> compensation =
        new LinkedHashMap<>();
    bindings.stream()
        .filter(binding -> binding.getDependencyType()
            == WorkflowDependencyType.COMPENSATION_SKILL)
        .forEach(binding -> compensation.put(binding.getNodeId(), binding));
    for (int index = 0; index < compiled.nodes().size(); index++) {
      var compiledNode = compiled.nodes().get(index);
      AiWorkflowNodeExecution node = new AiWorkflowNodeExecution();
      node.setExecutionId(execution.getId());
      node.setNodeId(compiledNode.id());
      node.setNodeType(compiledNode.type());
      node.setNodeOrder(index);
      node.setStatus(WorkflowNodeExecutionStatus.PENDING);
      node.setAttemptCount(0);
      AiWorkflowDependencyBinding compensationBinding =
          compensation.get(compiledNode.id());
      if (compensationBinding != null) {
        node.setCompensationSkillId(
            compensationBinding.getResourceId()
        );
        node.setCompensationSkillVersionId(
            compensationBinding.getResourceVersionId()
        );
        node.setCompensationContentHash(
            compensationBinding.getResourceContentHash()
        );
      }
      nodeRepository.save(node);
    }
  }

  private Map<String, Object> planSnapshot(
      final Map<String, Object> manifest,
      final List<AiWorkflowDependencyBinding> bindings
  ) {
    List<Map<String, Object>> dependencies = bindings.stream()
        .map(binding -> Map.<String, Object>ofEntries(
            Map.entry("nodeId", binding.getNodeId()),
            Map.entry("type", binding.getDependencyType().name()),
            Map.entry("resourceId", binding.getResourceId()),
            Map.entry(
                "resourceVersionId",
                binding.getResourceVersionId()
            ),
            Map.entry(
                "resourceContentHash",
                binding.getResourceContentHash()
            )
        ))
        .toList();
    return Map.of(
        "manifest", manifest,
        "dependencies", dependencies
    );
  }

  private void assertDependencySnapshot(
      final CompiledWorkflow compiled,
      final List<AiWorkflowDependencyBinding> bindings
  ) {
    if (compiled.dependencies().size() != bindings.size()) {
      throw new IllegalStateException(
          "Workflow dependency snapshot is incomplete"
      );
    }
    for (int index = 0; index < bindings.size(); index++) {
      var expected = compiled.dependencies().get(index);
      var actual = bindings.get(index);
      if (!expected.nodeId().equals(actual.getNodeId())
          || expected.type() != actual.getDependencyType()
          || !expected.resourceId().equals(actual.getResourceId())
          || !expected.resourceVersionId().equals(
              actual.getResourceVersionId())) {
        throw new IllegalStateException(
            "Workflow dependency snapshot order has changed"
        );
      }
    }
  }

  private AiWorkflowDefinition requireWorkflow(
      final String workflowId,
      final ScopeAssignment scope,
      final boolean forUpdate
  ) {
    AiWorkflowDefinition workflow = (forUpdate
        ? workflowRepository.findActiveByIdForUpdate(required(
            workflowId,
            "Workflow ID",
            64
        ))
        : workflowRepository.findActiveById(required(
            workflowId,
            "Workflow ID",
            64
        ))).orElseThrow(() -> new IllegalArgumentException(
            "Workflow does not exist"
        ));
    if (workflow.getScopeType() != scope.scopeType()
        || !Objects.equals(workflow.getTenantId(), scope.tenantId())) {
      throw new IllegalArgumentException(
          "Workflow does not exist in the current scope"
      );
    }
    if (forUpdate) {
      scopeAccessPolicy.assertCanManageOwnedResource(
          workflow.getScopeType(),
          workflow.getTenantId()
      );
    } else {
      scopeAccessPolicy.assertCanReadManagedResource(
          workflow.getScopeType(),
          workflow.getTenantId()
      );
    }
    return workflow;
  }

  private AiWorkflowExecution requireVisibleExecution(
      final String workflowId,
      final String executionId,
      final boolean forUpdate
  ) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiWorkflowDefinition workflow =
        requireWorkflow(workflowId, scope, forUpdate);
    AiWorkflowExecution execution = (forUpdate
        ? executionRepository.findActiveByIdForUpdate(required(
            executionId,
            "Workflow execution ID",
            64
        ))
        : executionRepository.findActiveById(required(
            executionId,
            "Workflow execution ID",
            64
        ))).orElseThrow(() -> new IllegalArgumentException(
            "Workflow execution does not exist"
        ));
    if (!workflow.getId().equals(execution.getWorkflowId())
        || execution.getScopeType() != scope.scopeType()
        || !Objects.equals(execution.getTenantId(), scope.tenantId())) {
      throw new IllegalArgumentException(
          "Workflow execution does not exist"
      );
    }
    return execution;
  }

  private AiWorkflowExecution decorateSummary(
      final AiWorkflowExecution execution
  ) {
    execution.setInput(null);
    execution.setOutput(readOptional(execution.getOutputJson()));
    execution.setNodes(null);
    execution.setHumanTasks(null);
    return execution;
  }

  private AiWorkflowExecution decorate(
      final AiWorkflowExecution execution
  ) {
    execution.setInput(readMap(
        execution.getInputJson(),
        "Workflow execution input"
    ));
    execution.setOutput(readOptional(execution.getOutputJson()));
    execution.setNodes(
        nodeRepository.findAllActiveByExecutionId(execution.getId())
            .stream()
            .map(this::decorateNode)
            .toList()
    );
    execution.setHumanTasks(
        taskRepository.findAllActiveByExecutionId(execution.getId())
            .stream()
            .map(this::decorateTask)
            .toList()
    );
    return execution;
  }

  private AiWorkflowNodeExecution decorateNode(
      final AiWorkflowNodeExecution node
  ) {
    node.setInput(readMapOptional(node.getInputJson()));
    node.setOutput(readOptional(node.getOutputJson()));
    return node;
  }

  private AiWorkflowHumanTask decorateTask(
      final AiWorkflowHumanTask task
  ) {
    task.setInputSchema(readMap(
        task.getInputSchemaJson(),
        "Human task schema"
    ));
    task.setContext(readOptional(task.getContextJson()));
    task.setOutput(readOptional(task.getOutputJson()));
    return task;
  }

  private void assertPayloadSize(
      final String json,
      final String label
  ) {
    int maximum = properties.getMaximumPayloadBytes() == null
        ? 1024 * 1024 : properties.getMaximumPayloadBytes();
    if (json.getBytes(StandardCharsets.UTF_8).length > maximum) {
      throw new IllegalArgumentException(label + " is too large");
    }
  }

  private Map<String, Object> readMap(
      final String value,
      final String label
  ) {
    try {
      return objectMapper.readValue(value, MAP_TYPE);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(label + " is invalid", ex);
    }
  }

  private Map<String, Object> readMapOptional(final String value) {
    if (value == null) {
      return null;
    }
    return readMap(value, "Stored Workflow node input");
  }

  private Object readOptional(final String value) {
    if (value == null) {
      return null;
    }
    try {
      return objectMapper.readValue(value, Object.class);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(
          "Stored Workflow JSON is invalid",
          ex
      );
    }
  }

  private String write(final Object value) {
    try {
      return canonicalMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException(
          "Workflow value is not serializable",
          ex
      );
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(
      final Object value,
      final String label
  ) {
    if (!(value instanceof Map<?, ?>)) {
      throw new IllegalStateException(label + " must be an object");
    }
    return (Map<String, Object>) value;
  }

  private static void assertNotTerminal(
      final AiWorkflowExecution execution
  ) {
    if (SetHolder.TERMINAL.contains(execution.getStatus())) {
      throw new IllegalStateException(
          "Workflow execution is already terminal"
      );
    }
  }

  private static boolean safeToPause(
      final WorkflowExecutionStatus status
  ) {
    return status != WorkflowExecutionStatus.RUNNING;
  }

  private static void clearLease(
      final AiWorkflowExecution execution
  ) {
    execution.setLeaseOwner(null);
    execution.setLeaseExpiresAt(null);
    execution.setNextPollAt(null);
  }

  private static String currentUserId() {
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    if (context == null
        || context.getUserId() == null
        || context.getUserId().isBlank()) {
      return "system";
    }
    return context.getUserId();
  }

  private static String optional(
      final String value,
      final int maximum
  ) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.trim();
    if (normalized.length() > maximum) {
      throw new IllegalArgumentException(
          "Workflow text value is too long"
      );
    }
    return normalized;
  }

  private static String required(
      final String value,
      final String label,
      final int maximum
  ) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " is required");
    }
    String normalized = value.trim();
    if (normalized.length() > maximum) {
      throw new IllegalArgumentException(label + " is too long");
    }
    return normalized;
  }

  private static String sha256(final String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256")
              .digest(value.getBytes(StandardCharsets.UTF_8))
      );
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable", ex);
    }
  }

  private static final class SetHolder {

    private static final java.util.Set<WorkflowExecutionStatus> TERMINAL =
        java.util.Set.of(
            WorkflowExecutionStatus.SUCCEEDED,
            WorkflowExecutionStatus.FAILED,
            WorkflowExecutionStatus.CANCELLED
        );

    private SetHolder() {
    }
  }
}
