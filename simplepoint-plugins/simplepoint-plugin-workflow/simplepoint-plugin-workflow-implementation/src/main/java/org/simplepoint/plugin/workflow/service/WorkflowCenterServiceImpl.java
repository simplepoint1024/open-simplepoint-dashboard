package org.simplepoint.plugin.workflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantService;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.WorkflowException.Code;
import org.simplepoint.plugin.workflow.api.constants.WorkflowResourceCodes;
import org.simplepoint.plugin.workflow.api.constants.WorkflowVariableNames;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.ActivityState;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CompleteTaskCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CreateDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.DefinitionDetail;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.DefinitionPermissions;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.DefinitionSummary;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.InstanceDiagram;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PageResult;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.ProcessInstanceSummary;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PublishDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.RuntimePermissions;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.RuntimeForm;
import org.simplepoint.plugin.workflow.api.model.WorkflowFormContract;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.StartProcessCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.TaskSummary;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.UpdateDraftCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.VersionDetail;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.VersionSummary;
import org.simplepoint.plugin.workflow.api.service.WorkflowCenterService;
import org.simplepoint.plugin.workflow.api.service.WorkflowFormProvider;
import org.simplepoint.plugin.workflow.entity.WorkflowDefinitionEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowDefinitionVersionEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowInstanceEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowInstanceWatcherEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowNotificationOutboxEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Flowable-backed implementation with platform-owned version and tenancy semantics. */
@Service
@Transactional(readOnly = true)
public class WorkflowCenterServiceImpl implements WorkflowCenterService {

  private static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{2,79}");
  private static final int MAX_PAGE_SIZE = 100;
  private static final int MAX_VARIABLE_BYTES = 128 * 1024;

  private final EntityManager entityManager;
  private final RepositoryService repositoryService;
  private final RuntimeService runtimeService;
  private final TaskService taskService;
  private final HistoryService historyService;
  private final BpmnDefinitionValidator bpmnValidator;
  private final ObjectMapper objectMapper;
  private final TenantService tenantService;
  private final WorkflowFormContracts formContracts;
  private final Map<String, WorkflowFormProvider> formProviders;

  /** Creates the workflow domain service. */
  public WorkflowCenterServiceImpl(
      final EntityManager entityManager,
      final RepositoryService repositoryService,
      final RuntimeService runtimeService,
      final TaskService taskService,
      final HistoryService historyService,
      final BpmnDefinitionValidator bpmnValidator,
      final ObjectMapper objectMapper,
      final TenantService tenantService,
      final List<WorkflowFormProvider> formProviders
  ) {
    this.entityManager = entityManager;
    this.repositoryService = repositoryService;
    this.runtimeService = runtimeService;
    this.taskService = taskService;
    this.historyService = historyService;
    this.bpmnValidator = bpmnValidator;
    this.objectMapper = objectMapper;
    this.tenantService = tenantService;
    this.formContracts = new WorkflowFormContracts(objectMapper);
    this.formProviders = formProviders.stream().collect(Collectors.toUnmodifiableMap(
        WorkflowFormProvider::provider, Function.identity()));
  }

  @Override
  public PageResult<DefinitionSummary> definitions(
      final String keyword,
      final int page,
      final int size
  ) {
    String tenant = tenantId();
    final PageBounds bounds = PageBounds.of(page, size);
    Search search = Search.of(keyword);
    String where = "d.tenantId = :tenant and (:search = '' or lower(d.key) like :pattern"
        + " escape '!' or lower(d.name) like :pattern escape '!' or"
        + " lower(coalesce(d.description, '')) like :pattern escape '!')";
    List<WorkflowDefinitionEntity> rows = entityManager.createQuery(
            "select d from WorkflowDefinitionEntity d where " + where
                + " order by d.updatedAt desc, d.id desc",
            WorkflowDefinitionEntity.class
        ).setParameter("tenant", tenant)
        .setParameter("search", search.value())
        .setParameter("pattern", search.pattern())
        .setFirstResult(bounds.offset())
        .setMaxResults(bounds.size())
        .getResultList();
    long total = entityManager.createQuery(
            "select count(d) from WorkflowDefinitionEntity d where " + where,
            Long.class
        ).setParameter("tenant", tenant)
        .setParameter("search", search.value())
        .setParameter("pattern", search.pattern())
        .getSingleResult();
    return new PageResult<>(rows.stream().map(this::definitionSummary).toList(),
        bounds.page(), bounds.size(), total);
  }

  @Override
  public DefinitionPermissions definitionPermissions() {
    tenantId();
    return new DefinitionPermissions(
        allowed(WorkflowResourceCodes.DEFINITIONS_CREATE),
        allowed(WorkflowResourceCodes.DEFINITIONS_EDIT),
        allowed(WorkflowResourceCodes.DEFINITIONS_PUBLISH),
        allowed(WorkflowResourceCodes.DEFINITIONS_COPY_VERSION),
        allowed(WorkflowResourceCodes.INSTANCES_START)
    );
  }

  @Override
  public RuntimePermissions runtimePermissions() {
    tenantId();
    return new RuntimePermissions(
        allowed(WorkflowResourceCodes.TASKS_CLAIM),
        allowed(WorkflowResourceCodes.TASKS_COMPLETE),
        allowed(WorkflowResourceCodes.INSTANCES_VIEW),
        allowed(WorkflowResourceCodes.INSTANCES_CANCEL)
    );
  }

  @Override
  @Transactional
  public DefinitionDetail createDefinition(final CreateDefinitionCommand command) {
    requireAuthority(WorkflowResourceCodes.DEFINITIONS_CREATE);
    String tenant = tenantId();
    if (command == null) {
      throw invalid("请填写流程定义");
    }
    String key = required(command.key(), "流程标识不能为空", 80);
    if (!KEY.matcher(key).matches()) {
      throw invalid("流程标识必须以字母开头，只能包含字母、数字、下划线和短横线");
    }
    Long duplicates = entityManager.createQuery(
            "select count(d) from WorkflowDefinitionEntity d"
                + " where d.tenantId = :tenant and d.key = :key",
            Long.class
        ).setParameter("tenant", tenant)
        .setParameter("key", key)
        .getSingleResult();
    if (duplicates > 0L) {
      throw conflict("当前工作空间已存在流程标识 " + key);
    }
    String name = required(command.name(), "流程名称不能为空", 160);
    Instant now = Instant.now();
    WorkflowDefinitionEntity definition = new WorkflowDefinitionEntity();
    definition.setId(UUID.randomUUID().toString());
    definition.setTenantId(tenant);
    definition.setKey(key);
    definition.setName(name);
    definition.setCategory(optional(command.category(), 80, "流程分类"));
    definition.setDescription(optional(command.description(), 1000, "流程说明"));
    definition.setStatus("DRAFT");
    definition.setLatestVersion(0);
    definition.setDraftRevision(1L);
    definition.setDraftXml(WorkflowBpmnTemplates.approval(key, name));
    definition.setDraftFormContract(formContracts.write(WorkflowFormContract.empty()));
    definition.setCreatedBy(userId());
    definition.setCreatedAt(now);
    definition.setUpdatedAt(now);
    entityManager.persist(definition);
    return detail(definition);
  }

  @Override
  public DefinitionDetail definition(final String definitionId) {
    return detail(requireDefinition(definitionId, false));
  }

  @Override
  @Transactional
  public DefinitionDetail updateDraft(
      final String definitionId,
      final UpdateDraftCommand command
  ) {
    requireAuthority(WorkflowResourceCodes.DEFINITIONS_EDIT);
    if (command == null) {
      throw invalid("请提交流程草稿");
    }
    WorkflowDefinitionEntity definition = requireDefinition(definitionId, true);
    requireRevision(definition, command.expectedRevision());
    String xml = required(command.bpmnXml(), "BPMN XML 不能为空", 2_000_000);
    var validated = bpmnValidator.validate(xml, definition.getKey());
    // Omitted contracts preserve existing integrations for older API clients, rather than clearing them.
    String contract = command.formContract() == null ? definition.getDraftFormContract()
        : formContracts.write(command.formContract());
    formContracts.validateTaskBindings(formContracts.read(contract), validated.userTaskIds());
    definition.setDraftFormContract(formContracts.write(formContracts.read(contract)));
    definition.setName(required(command.name(), "流程名称不能为空", 160));
    definition.setCategory(optional(command.category(), 80, "流程分类"));
    definition.setDescription(optional(command.description(), 1000, "流程说明"));
    definition.setDraftXml(xml);
    definition.setDraftRevision(definition.getDraftRevision() + 1L);
    definition.setUpdatedAt(Instant.now());
    return detail(definition);
  }

  @Override
  @Transactional
  public VersionSummary publish(
      final String definitionId,
      final PublishDefinitionCommand command
  ) {
    requireAuthority(WorkflowResourceCodes.DEFINITIONS_PUBLISH);
    if (command == null) {
      throw invalid("请提交发布信息");
    }
    WorkflowDefinitionEntity definition = requireDefinition(definitionId, true);
    requireRevision(definition, command.expectedRevision());
    var validated = bpmnValidator.validate(definition.getDraftXml(), definition.getKey());
    formContracts.validateTaskBindings(formContracts.read(definition.getDraftFormContract()), validated.userTaskIds());
    definition.setDraftFormContract(formContracts.write(formContracts.read(definition.getDraftFormContract())));
    String checksum = snapshotChecksum(definition);
    if (checksum.equals(definition.getPublishedChecksum()) && definition.getLatestVersion() > 0) {
      return versionSummary(requireVersion(definition, definition.getLatestVersion()));
    }
    int nextVersion = definition.getLatestVersion() + 1;
    String comment = optional(command.comment(), 500, "发布说明");
    try {
      Deployment deployment = repositoryService.createDeployment()
          .tenantId(definition.getTenantId())
          .name(definition.getName() + " v" + nextVersion)
          .category(definition.getCategory())
          .addString(definition.getKey() + ".bpmn20.xml", definition.getDraftXml())
          .deploy();
      List<ProcessDefinition> deployed = repositoryService.createProcessDefinitionQuery()
          .deploymentId(deployment.getId())
          .list();
      if (deployed.size() != 1 || !definition.getKey().equals(deployed.get(0).getKey())) {
        throw new WorkflowException(Code.ENGINE_FAILURE, "Flowable 部署结果与流程标识不一致");
      }
      ProcessDefinition processDefinition = deployed.get(0);
      WorkflowDefinitionVersionEntity version = new WorkflowDefinitionVersionEntity();
      version.setId(UUID.randomUUID().toString());
      version.setDefinitionId(definition.getId());
      version.setTenantId(definition.getTenantId());
      version.setVersion(nextVersion);
      version.setBpmnXml(definition.getDraftXml());
      version.setFormContract(formContracts.write(formContracts.read(definition.getDraftFormContract())));
      version.setDefinitionName(definition.getName());
      version.setDefinitionCategory(definition.getCategory());
      version.setDefinitionDescription(definition.getDescription());
      version.setChecksumVersion(2);
      version.setChecksum(checksum);
      version.setComment(comment);
      version.setFlowableDeploymentId(deployment.getId());
      version.setFlowableProcessDefinitionId(processDefinition.getId());
      version.setPublishedBy(userId());
      version.setPublishedAt(Instant.now());
      entityManager.persist(version);
      definition.setLatestVersion(nextVersion);
      definition.setPublishedChecksum(checksum);
      definition.setStatus("PUBLISHED");
      definition.setUpdatedAt(version.getPublishedAt());
      return versionSummary(version);
    } catch (WorkflowException exception) {
      throw exception;
    } catch (FlowableException exception) {
      throw new WorkflowException(Code.ENGINE_FAILURE, "流程部署失败，请检查 BPMN 结构", exception);
    }
  }

  @Override
  public VersionDetail version(final String definitionId, final int version) {
    WorkflowDefinitionEntity definition = requireDefinition(definitionId, false);
    WorkflowDefinitionVersionEntity item = requireVersion(definition, version);
    return new VersionDetail(versionSummary(item), item.getBpmnXml(), item.getDefinitionName(),
        item.getDefinitionCategory(), item.getDefinitionDescription(), formContracts.read(item.getFormContract()));
  }

  @Override
  @Transactional
  public DefinitionDetail copyVersionToDraft(
      final String definitionId,
      final int version,
      final long expectedRevision
  ) {
    requireAuthority(WorkflowResourceCodes.DEFINITIONS_COPY_VERSION);
    WorkflowDefinitionEntity definition = requireDefinition(definitionId, true);
    requireRevision(definition, expectedRevision);
    WorkflowDefinitionVersionEntity source = requireVersion(definition, version);
    definition.setDraftXml(source.getBpmnXml());
    definition.setDraftFormContract(formContracts.write(formContracts.read(source.getFormContract())));
    // Legacy versions have no metadata snapshot; never fabricate historical metadata.
    if (source.getDefinitionName() != null) {
      definition.setName(source.getDefinitionName());
      definition.setCategory(source.getDefinitionCategory());
      definition.setDescription(source.getDefinitionDescription());
    }
    definition.setDraftRevision(definition.getDraftRevision() + 1L);
    definition.setUpdatedAt(Instant.now());
    return detail(definition);
  }

  @Override
  @Transactional
  public ProcessInstanceSummary start(
      final String definitionId,
      final StartProcessCommand command
  ) {
    requireAuthority(WorkflowResourceCodes.INSTANCES_START);
    WorkflowDefinitionEntity definition = requireDefinition(definitionId, true);
    if (definition.getLatestVersion() < 1) {
      throw conflict("流程尚未发布");
    }
    int requestedVersion = command == null || command.version() == null
        ? definition.getLatestVersion() : command.version();
    WorkflowDefinitionVersionEntity version = requireVersion(definition, requestedVersion);
    String businessKey = command == null ? null
        : optional(command.businessKey(), 160, "业务标识");
    String versionName = version.getDefinitionName() == null ? definition.getName() : version.getDefinitionName();
    String title = command == null ? versionName
        : optional(command.title(), 240, "事项标题");
    if (title == null) {
      title = versionName;
    }
    Map<String, Object> variables = safeVariables(command == null ? null : command.variables());
    WorkflowFormContract contract = formContracts.read(version.getFormContract());
    variables = formContracts.submission(contract, variables, Map.of(), "start");
    validateDirectoryVariables(contract, variables, definition.getTenantId());
    validateExternalForm(contract.startBinding(), "start", null, variables);
    String starter = userId();
    Set<String> watchers = copiedUsers(command, definition.getTenantId(), starter);
    variables.put("initiator", starter);
    variables.put("workflowDefinitionId", definition.getId());
    variables.put("workflowVersion", requestedVersion);
    variables.put("workflowTitle", title);
    try {
      ProcessInstance process = runtimeService.startProcessInstanceById(
          version.getFlowableProcessDefinitionId(), businessKey, variables
      );
      WorkflowInstanceEntity instance = new WorkflowInstanceEntity();
      instance.setProcessInstanceId(process.getId());
      instance.setTenantId(definition.getTenantId());
      instance.setDefinitionId(definition.getId());
      instance.setDefinitionVersionId(version.getId());
      instance.setDefinitionKey(definition.getKey());
      instance.setDefinitionName(versionName);
      instance.setDefinitionVersion(version.getVersion());
      instance.setBusinessKey(businessKey);
      instance.setTitle(title);
      instance.setStarterId(starter);
      instance.setStartedAt(Instant.now());
      boolean running = runtimeService.createProcessInstanceQuery()
          .processInstanceId(process.getId())
          .singleResult() != null;
      instance.setStatus(running ? "RUNNING" : "COMPLETED");
      if (!running) {
        instance.setEndedAt(Instant.now());
      }
      entityManager.persist(instance);
      persistWatchers(instance, watchers);
      enqueueCopiedNotifications(instance, watchers);
      if (running) {
        enqueueTaskNotifications(instance);
      } else {
        enqueueInstanceCompleted(instance);
      }
      return instanceSummary(instance);
    } catch (FlowableException exception) {
      throw new WorkflowException(Code.ENGINE_FAILURE, "流程启动失败", exception);
    }
  }

  @Override
  public RuntimeForm startForm(final String definitionId, final Integer requestedVersion) {
    requireAuthority(WorkflowResourceCodes.INSTANCES_START);
    WorkflowDefinitionEntity definition = requireDefinition(definitionId, false);
    WorkflowDefinitionVersionEntity version = requireVersion(definition,
        requestedVersion == null ? definition.getLatestVersion() : requestedVersion);
    WorkflowFormContract contract = formContracts.read(version.getFormContract());
    return new RuntimeForm(version.getVersion(), contract, contract.startBinding(),
        formContracts.project(contract, Map.of(), "start"));
  }

  @Override
  public RuntimeForm taskForm(final String taskId) {
    requireAuthority(WorkflowResourceCodes.TASKS_COMPLETE);
    Task task = requireTask(taskId, tenantId());
    if (hasText(task.getAssignee()) ? !userId().equals(task.getAssignee())
        : !isCandidate(taskId, userId(), roles())) {
      throw forbidden("仅当前办理人或候选人可以读取任务表单");
    }
    WorkflowInstanceEntity instance = requireInstance(task.getProcessInstanceId());
    WorkflowDefinitionVersionEntity version = requireVersion(
        requireDefinition(instance.getDefinitionId(), false), instance.getDefinitionVersion());
    WorkflowFormContract contract = formContracts.read(version.getFormContract());
    return new RuntimeForm(version.getVersion(), contract, contract.taskBindings().get(task.getTaskDefinitionKey()),
        formContracts.project(contract, runtimeService.getVariables(instance.getProcessInstanceId()), "task"));
  }

  @Override
  public PageResult<TaskSummary> pendingTasks(
      final String keyword,
      final int page,
      final int size
  ) {
    String tenant = tenantId();
    String user = userId();
    final PageBounds bounds = PageBounds.of(page, size);
    List<String> groups = roles();
    var query = taskService.createTaskQuery().taskTenantId(tenant).active().or()
        .taskAssignee(user)
        .taskCandidateUser(user);
    if (!groups.isEmpty()) {
      query.taskCandidateGroupIn(groups);
    }
    query.endOr();
    if (hasText(keyword)) {
      query.taskNameLikeIgnoreCase("%" + escapeLike(keyword.trim()) + "%");
    }
    long total = query.count();
    List<Task> tasks = query.orderByTaskCreateTime().desc()
        .listPage(bounds.offset(), bounds.size());
    Map<String, WorkflowInstanceEntity> instances = instancesByIds(
        tasks.stream().map(Task::getProcessInstanceId).toList()
    );
    return new PageResult<>(tasks.stream()
        .map(task -> taskSummary(task, instances.get(task.getProcessInstanceId()), user, groups))
        .toList(), bounds.page(), bounds.size(), total);
  }

  @Override
  public PageResult<TaskSummary> completedTasks(
      final String keyword,
      final int page,
      final int size
  ) {
    String tenant = tenantId();
    String user = userId();
    PageBounds bounds = PageBounds.of(page, size);
    var query = historyService.createHistoricTaskInstanceQuery()
        .taskTenantId(tenant)
        .taskAssignee(user)
        .finished();
    if (hasText(keyword)) {
      query.taskNameLikeIgnoreCase("%" + escapeLike(keyword.trim()) + "%");
    }
    long total = query.count();
    List<HistoricTaskInstance> tasks = query.orderByHistoricTaskInstanceEndTime().desc()
        .listPage(bounds.offset(), bounds.size());
    Map<String, WorkflowInstanceEntity> instances = instancesByIds(
        tasks.stream().map(HistoricTaskInstance::getProcessInstanceId).toList()
    );
    return new PageResult<>(tasks.stream()
        .map(task -> taskSummary(task, instances.get(task.getProcessInstanceId())))
        .toList(), bounds.page(), bounds.size(), total);
  }

  @Override
  @Transactional
  public TaskSummary claim(final String taskId) {
    requireAuthority(WorkflowResourceCodes.TASKS_CLAIM);
    String tenant = tenantId();
    String user = userId();
    List<String> groups = roles();
    Task task = requireTask(taskId, tenant);
    if (hasText(task.getAssignee()) && !user.equals(task.getAssignee())) {
      throw forbidden("该任务已由其他用户办理");
    }
    if (!hasText(task.getAssignee()) && !isCandidate(taskId, user, groups)) {
      throw forbidden("当前用户不在任务候选范围内");
    }
    if (!hasText(task.getAssignee())) {
      taskService.claim(taskId, user);
      task = requireTask(taskId, tenant);
    }
    WorkflowInstanceEntity instance = requireInstance(task.getProcessInstanceId());
    return taskSummary(task, instance, user, groups);
  }

  @Override
  @Transactional
  public ProcessInstanceSummary complete(
      final String taskId,
      final CompleteTaskCommand command
  ) {
    requireAuthority(WorkflowResourceCodes.TASKS_COMPLETE);
    String tenant = tenantId();
    String user = userId();
    List<String> groups = roles();
    Task task = requireTask(taskId, tenant);
    if (!hasText(task.getAssignee())) {
      if (!isCandidate(taskId, user, groups)) {
        throw forbidden("当前用户不在任务候选范围内");
      }
      taskService.claim(taskId, user);
    } else if (!user.equals(task.getAssignee())) {
      throw forbidden("仅当前办理人可以提交任务");
    }
    WorkflowInstanceEntity instance = requireInstance(task.getProcessInstanceId());
    String comment = command == null ? null : optional(command.comment(), 2000, "办理意见");
    if (comment != null) {
      taskService.addComment(taskId, task.getProcessInstanceId(), comment);
    }
    try {
      WorkflowDefinitionVersionEntity version = requireVersion(
          requireDefinition(instance.getDefinitionId(), false), instance.getDefinitionVersion());
      WorkflowFormContract contract = formContracts.read(version.getFormContract());
      Map<String, Object> variables = formContracts.submission(contract,
          safeVariables(command == null ? null : command.variables()),
          runtimeService.getVariables(instance.getProcessInstanceId()), "task");
      validateDirectoryVariables(contract, variables, tenant);
      validateExternalForm(contract.taskBindings().get(task.getTaskDefinitionKey()), "task",
          task.getTaskDefinitionKey(), variables);
      taskService.complete(taskId, variables);
    } catch (FlowableException exception) {
      throw new WorkflowException(Code.ENGINE_FAILURE, "任务提交失败", exception);
    }
    ProcessInstance running = runtimeService.createProcessInstanceQuery()
        .processInstanceId(instance.getProcessInstanceId())
        .singleResult();
    if (running == null) {
      instance.setStatus("COMPLETED");
      instance.setEndedAt(Instant.now());
      enqueueInstanceCompleted(instance);
    } else {
      enqueueTaskNotifications(instance);
    }
    return instanceSummary(instance);
  }

  @Override
  public PageResult<ProcessInstanceSummary> startedInstances(
      final String keyword,
      final int page,
      final int size
  ) {
    requireAuthority(WorkflowResourceCodes.INSTANCES_STARTED_VIEW);
    return instancePage(keyword, null, userId(), page, size);
  }

  @Override
  public PageResult<ProcessInstanceSummary> copiedInstances(
      final String keyword,
      final int page,
      final int size
  ) {
    requireAuthority(WorkflowResourceCodes.INSTANCES_STARTED_VIEW);
    String tenant = tenantId();
    String user = userId();
    final PageBounds bounds = PageBounds.of(page, size);
    Search search = Search.of(keyword);
    String where = "w.tenantId = :tenant and w.userId = :user"
        + " and w.processInstanceId = i.processInstanceId"
        + " and (:search = '' or lower(i.title) like :pattern escape '!'"
        + " or lower(i.definitionName) like :pattern escape '!'"
        + " or lower(coalesce(i.businessKey, '')) like :pattern escape '!')";
    List<WorkflowInstanceEntity> rows = entityManager.createQuery(
            "select i from WorkflowInstanceEntity i, WorkflowInstanceWatcherEntity w where "
                + where + " order by i.startedAt desc, i.processInstanceId desc",
            WorkflowInstanceEntity.class
        ).setParameter("tenant", tenant)
        .setParameter("user", user)
        .setParameter("search", search.value())
        .setParameter("pattern", search.pattern())
        .setFirstResult(bounds.offset())
        .setMaxResults(bounds.size())
        .getResultList();
    long total = entityManager.createQuery(
            "select count(i) from WorkflowInstanceEntity i, WorkflowInstanceWatcherEntity w where "
                + where, Long.class
        ).setParameter("tenant", tenant)
        .setParameter("user", user)
        .setParameter("search", search.value())
        .setParameter("pattern", search.pattern())
        .getSingleResult();
    return new PageResult<>(rows.stream().map(this::instanceSummary).toList(),
        bounds.page(), bounds.size(), total);
  }

  @Override
  public PageResult<ProcessInstanceSummary> instances(
      final String keyword,
      final String status,
      final int page,
      final int size
  ) {
    requireAuthority(WorkflowResourceCodes.INSTANCES_VIEW);
    return instancePage(keyword, status, null, page, size);
  }

  @Override
  public InstanceDiagram diagram(final String processInstanceId) {
    WorkflowInstanceEntity instance = requireInstance(processInstanceId);
    requireInstanceVisibility(instance);
    WorkflowDefinitionVersionEntity version = entityManager.find(
        WorkflowDefinitionVersionEntity.class, instance.getDefinitionVersionId()
    );
    if (version == null || !instance.getTenantId().equals(version.getTenantId())) {
      throw notFound("流程版本不存在");
    }
    List<String> activeIds = "RUNNING".equals(instance.getStatus())
        ? runtimeService.getActiveActivityIds(processInstanceId) : List.of();
    Set<String> activeSet = Set.copyOf(activeIds);
    List<HistoricActivityInstance> history = historyService
        .createHistoricActivityInstanceQuery()
        .processInstanceId(processInstanceId)
        .orderByHistoricActivityInstanceStartTime()
        .asc()
        .list();
    List<ActivityState> activities = history.stream().map(activity -> new ActivityState(
        activity.getActivityId(),
        activity.getActivityName(),
        activity.getActivityType(),
        activeSet.contains(activity.getActivityId()) ? "ACTIVE"
            : activity.getEndTime() == null ? "CANCELED" : "COMPLETED",
        instant(activity.getStartTime()),
        instant(activity.getEndTime()),
        activity.getAssignee()
    )).toList();
    return new InstanceDiagram(
        instanceSummary(instance), version.getBpmnXml(), activities, activeIds
    );
  }

  @Override
  @Transactional
  public ProcessInstanceSummary cancel(
      final String processInstanceId,
      final String reason
  ) {
    requireAuthority(WorkflowResourceCodes.INSTANCES_CANCEL);
    WorkflowInstanceEntity instance = requireInstance(processInstanceId);
    if (!allowed(WorkflowResourceCodes.INSTANCES_VIEW)
        && !userId().equals(instance.getStarterId())) {
      throw forbidden("仅流程发起人或流程管理员可以终止实例");
    }
    if (!"RUNNING".equals(instance.getStatus())) {
      throw conflict("仅运行中的流程可以终止");
    }
    String normalizedReason = required(reason, "请填写终止原因", 500);
    ProcessInstance running = runtimeService.createProcessInstanceQuery()
        .processInstanceId(processInstanceId)
        .processInstanceTenantId(instance.getTenantId())
        .singleResult();
    if (running == null) {
      instance.setStatus("COMPLETED");
      instance.setEndedAt(Instant.now());
      return instanceSummary(instance);
    }
    runtimeService.deleteProcessInstance(processInstanceId, normalizedReason);
    instance.setStatus("CANCELED");
    instance.setCancelReason(normalizedReason);
    instance.setEndedAt(Instant.now());
    return instanceSummary(instance);
  }

  private PageResult<ProcessInstanceSummary> instancePage(
      final String keyword,
      final String requestedStatus,
      final String starter,
      final int page,
      final int size
  ) {
    String tenant = tenantId();
    PageBounds bounds = PageBounds.of(page, size);
    Search search = Search.of(keyword);
    String status = normalizeStatus(requestedStatus);
    String where = "i.tenantId = :tenant and (:starter is null or i.starterId = :starter)"
        + " and (:status is null or i.status = :status)"
        + " and (:search = '' or lower(i.title) like :pattern escape '!'"
        + " or lower(i.definitionName) like :pattern escape '!'"
        + " or lower(coalesce(i.businessKey, '')) like :pattern escape '!')";
    var query = entityManager.createQuery(
        "select i from WorkflowInstanceEntity i where " + where
            + " order by i.startedAt desc, i.processInstanceId desc",
        WorkflowInstanceEntity.class
    );
    bindInstanceQuery(query, tenant, starter, status, search);
    List<WorkflowInstanceEntity> rows = query.setFirstResult(bounds.offset())
        .setMaxResults(bounds.size())
        .getResultList();
    var countQuery = entityManager.createQuery(
        "select count(i) from WorkflowInstanceEntity i where " + where, Long.class
    );
    bindInstanceQuery(countQuery, tenant, starter, status, search);
    return new PageResult<>(rows.stream().map(this::instanceSummary).toList(),
        bounds.page(), bounds.size(), countQuery.getSingleResult());
  }

  private static void bindInstanceQuery(
      final jakarta.persistence.Query query,
      final String tenant,
      final String starter,
      final String status,
      final Search search
  ) {
    query.setParameter("tenant", tenant)
        .setParameter("starter", starter)
        .setParameter("status", status)
        .setParameter("search", search.value())
        .setParameter("pattern", search.pattern());
  }

  private DefinitionDetail detail(final WorkflowDefinitionEntity definition) {
    List<VersionSummary> versions = entityManager.createQuery(
            "select new org.simplepoint.plugin.workflow.api.model.WorkflowModels$VersionSummary("
                + "v.id,v.version,v.checksum,v.comment,v.flowableProcessDefinitionId,v.publishedBy,v.publishedAt)"
                + " from WorkflowDefinitionVersionEntity v"
                + " where v.tenantId = :tenant and v.definitionId = :definition"
                + " order by v.version desc",
            VersionSummary.class
        ).setParameter("tenant", definition.getTenantId())
        .setParameter("definition", definition.getId())
        .getResultList();
    return new DefinitionDetail(definitionSummary(definition), definition.getDraftXml(),
        versions, definitionPermissions(), formContracts.read(definition.getDraftFormContract()));
  }

  private DefinitionSummary definitionSummary(final WorkflowDefinitionEntity definition) {
    String draftChecksum = definition.getDraftFormContract() == null
        ? checksum(definition.getDraftXml()) : snapshotChecksum(definition);
    return new DefinitionSummary(
        definition.getId(), definition.getKey(), definition.getName(),
        definition.getCategory(), definition.getDescription(), definition.getStatus(),
        definition.getLatestVersion(), definition.getDraftRevision(),
        !Objects.equals(draftChecksum, definition.getPublishedChecksum()),
        definition.getCreatedBy(), definition.getCreatedAt(), definition.getUpdatedAt()
    );
  }

  private VersionSummary versionSummary(final WorkflowDefinitionVersionEntity version) {
    return new VersionSummary(
        version.getId(), version.getVersion(), version.getChecksum(), version.getComment(),
        version.getFlowableProcessDefinitionId(), version.getPublishedBy(),
        version.getPublishedAt()
    );
  }

  private ProcessInstanceSummary instanceSummary(final WorkflowInstanceEntity instance) {
    return new ProcessInstanceSummary(
        instance.getProcessInstanceId(), instance.getDefinitionId(),
        instance.getDefinitionKey(), instance.getDefinitionName(),
        instance.getDefinitionVersion(), instance.getBusinessKey(), instance.getTitle(),
        instance.getStarterId(), instance.getStatus(), instance.getStartedAt(),
        instance.getEndedAt()
    );
  }

  private TaskSummary taskSummary(
      final Task task,
      final WorkflowInstanceEntity instance,
      final String user,
      final List<String> groups
  ) {
    boolean assigned = user.equals(task.getAssignee());
    boolean claimable = !hasText(task.getAssignee()) && isCandidate(task.getId(), user, groups);
    return new TaskSummary(
        task.getId(), task.getProcessInstanceId(), value(instance, WorkflowInstanceEntity::getDefinitionKey),
        value(instance, WorkflowInstanceEntity::getDefinitionName), task.getName(),
        task.getTaskDefinitionKey(), task.getAssignee(), task.getOwner(),
        instant(task.getCreateTime()), instant(task.getDueDate()), null, task.getPriority(),
        claimable, assigned || claimable,
        value(instance, WorkflowInstanceEntity::getBusinessKey),
        value(instance, WorkflowInstanceEntity::getTitle)
    );
  }

  private static TaskSummary taskSummary(
      final HistoricTaskInstance task,
      final WorkflowInstanceEntity instance
  ) {
    return new TaskSummary(
        task.getId(), task.getProcessInstanceId(), value(instance, WorkflowInstanceEntity::getDefinitionKey),
        value(instance, WorkflowInstanceEntity::getDefinitionName), task.getName(),
        task.getTaskDefinitionKey(), task.getAssignee(), task.getOwner(),
        instant(task.getCreateTime()), instant(task.getDueDate()), instant(task.getEndTime()),
        task.getPriority(), false, false,
        value(instance, WorkflowInstanceEntity::getBusinessKey),
        value(instance, WorkflowInstanceEntity::getTitle)
    );
  }

  private boolean isCandidate(
      final String taskId,
      final String user,
      final List<String> groups
  ) {
    if (taskService.createTaskQuery().taskId(taskId).taskCandidateUser(user).count() > 0L) {
      return true;
    }
    return !groups.isEmpty() && taskService.createTaskQuery()
        .taskId(taskId)
        .taskCandidateGroupIn(groups)
        .count() > 0L;
  }

  private Task requireTask(final String taskId, final String tenant) {
    Task task = taskService.createTaskQuery()
        .taskId(required(taskId, "任务 ID 不能为空", 64))
        .taskTenantId(tenant)
        .singleResult();
    if (task == null) {
      throw notFound("任务不存在或已办理");
    }
    return task;
  }

  private WorkflowDefinitionEntity requireDefinition(
      final String definitionId,
      final boolean lock
  ) {
    String id = required(definitionId, "流程定义 ID 不能为空", 36);
    WorkflowDefinitionEntity definition = lock
        ? entityManager.find(WorkflowDefinitionEntity.class, id, LockModeType.PESSIMISTIC_WRITE)
        : entityManager.find(WorkflowDefinitionEntity.class, id);
    if (definition == null || !tenantId().equals(definition.getTenantId())) {
      throw notFound("流程定义不存在");
    }
    return definition;
  }

  private WorkflowDefinitionVersionEntity requireVersion(
      final WorkflowDefinitionEntity definition,
      final int version
  ) {
    if (version < 1) {
      throw invalid("流程版本必须大于 0");
    }
    List<WorkflowDefinitionVersionEntity> rows = entityManager.createQuery(
            "select v from WorkflowDefinitionVersionEntity v"
                + " where v.tenantId = :tenant and v.definitionId = :definition"
                + " and v.version = :version",
            WorkflowDefinitionVersionEntity.class
        ).setParameter("tenant", definition.getTenantId())
        .setParameter("definition", definition.getId())
        .setParameter("version", version)
        .setMaxResults(1)
        .getResultList();
    if (rows.isEmpty()) {
      throw notFound("流程版本不存在");
    }
    return rows.get(0);
  }

  private WorkflowInstanceEntity requireInstance(final String processInstanceId) {
    WorkflowInstanceEntity instance = entityManager.find(
        WorkflowInstanceEntity.class,
        required(processInstanceId, "流程实例 ID 不能为空", 64)
    );
    if (instance == null || !tenantId().equals(instance.getTenantId())) {
      throw notFound("流程实例不存在");
    }
    return instance;
  }

  private Map<String, WorkflowInstanceEntity> instancesByIds(final Collection<String> ids) {
    Set<String> normalized = ids == null ? Set.of() : ids.stream()
        .filter(WorkflowCenterServiceImpl::hasText)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    if (normalized.isEmpty()) {
      return Map.of();
    }
    return entityManager.createQuery(
            "select i from WorkflowInstanceEntity i"
                + " where i.tenantId = :tenant and i.processInstanceId in :ids",
            WorkflowInstanceEntity.class
        ).setParameter("tenant", tenantId())
        .setParameter("ids", normalized)
        .getResultList().stream()
        .collect(Collectors.toMap(WorkflowInstanceEntity::getProcessInstanceId,
            Function.identity()));
  }

  private Map<String, Object> safeVariables(final Map<String, Object> requested) {
    Map<String, Object> variables = new LinkedHashMap<>();
    if (requested == null || requested.isEmpty()) {
      return variables;
    }
    if (requested.size() > 100) {
      throw invalid("流程变量不能超过 100 个");
    }
    requested.forEach((key, value) -> {
      String name = required(key, "流程变量名不能为空", 80);
      if (!name.matches("[A-Za-z_][A-Za-z0-9_.-]{0,79}")
          || WorkflowVariableNames.isReserved(name)) {
        throw invalid("流程变量名不允许使用: " + name);
      }
      variables.put(name, safeValue(value, 0));
    });
    try {
      if (objectMapper.writeValueAsBytes(variables).length > MAX_VARIABLE_BYTES) {
        throw invalid("流程变量总大小不能超过 128 KB");
      }
    } catch (IOException exception) {
      throw new WorkflowException(Code.VALIDATION, "流程变量无法序列化", exception);
    }
    return variables;
  }

  private String snapshotChecksum(final WorkflowDefinitionEntity definition) {
    try {
      return checksum(objectMapper.writeValueAsString(java.util.Arrays.asList(
          definition.getDraftXml(), definition.getName(), definition.getCategory(),
          definition.getDescription(), formContracts.read(definition.getDraftFormContract()))));
    } catch (IOException exception) {
      throw new WorkflowException(Code.VALIDATION, "流程快照无法序列化", exception);
    }
  }

  private void validateExternalForm(
      final WorkflowFormContract.Binding binding, final String stage,
      final String taskDefinitionKey, final Map<String, Object> variables
  ) {
    if (binding == null) {
      return;
    }
    WorkflowFormProvider provider = formProviders.get(binding.provider());
    if (provider == null) {
      throw conflict("此流程绑定的表单提供方尚未接入，暂时不能提交");
    }
    provider.validateSubmission(binding, stage, taskDefinitionKey,
        java.util.Collections.unmodifiableMap(variables));
  }

  private void validateDirectoryVariables(
      final WorkflowFormContract contract, final Map<String, Object> values, final String tenant
  ) {
    Set<String> users = new LinkedHashSet<>();
    Set<String> roles = new LinkedHashSet<>();
    for (WorkflowFormContract.Variable variable : contract.variables()) {
      Object value = values.get(variable.name());
      if (value instanceof String id && "user".equals(variable.type())) {
        users.add(id);
      } else if (value instanceof List<?> ids && "users".equals(variable.type())) {
        ids.forEach(id -> users.add((String) id));
      } else if (value instanceof String authority && "role".equals(variable.type())) {
        roles.add(authority);
      } else if (value instanceof List<?> authorities && "roles".equals(variable.type())) {
        authorities.forEach(authority -> roles.add((String) authority));
      }
    }
    List<String> requested = List.copyOf(users);
    for (int offset = 0; offset < requested.size(); offset += 50) {
      List<String> batch = requested.subList(offset, Math.min(offset + 50, requested.size()));
      if (!new LinkedHashSet<>(tenantService.resolveActiveMemberIds(tenant, batch)).containsAll(batch)) {
        throw invalid("流程变量中的用户必须是当前组织的有效成员");
      }
    }
    validateRoleAuthorities(tenant, roles);
  }

  private void validateRoleAuthorities(final String tenant, final Collection<String> roles) {
    List<String> requested = List.copyOf(roles);
    for (int offset = 0; offset < requested.size(); offset += 50) {
      List<String> batch = requested.subList(offset, Math.min(offset + 50, requested.size()));
      if (!new LinkedHashSet<>(tenantService.resolveActiveRoleAuthorities(tenant, batch)).containsAll(batch)) {
        throw invalid("任务候选角色和流程变量中的角色必须属于当前组织且未被删除");
      }
    }
  }

  private Object safeValue(final Object value, final int depth) {
    if (value instanceof Number number && !Double.isFinite(number.doubleValue())) {
      throw invalid("流程变量数值必须是有限数值");
    }
    if (value == null || value instanceof String || value instanceof Number
        || value instanceof Boolean) {
      return value;
    }
    if (depth >= 8) {
      throw invalid("流程变量嵌套不能超过 8 层");
    }
    if (value instanceof List<?> list) {
      if (list.size() > 500) {
        throw invalid("流程变量数组不能超过 500 项");
      }
      return list.stream().map(item -> safeValue(item, depth + 1)).toList();
    }
    if (value instanceof Map<?, ?> map) {
      if (map.size() > 200) {
        throw invalid("流程变量对象不能超过 200 个字段");
      }
      Map<String, Object> result = new LinkedHashMap<>();
      map.forEach((key, item) -> {
        if (!(key instanceof String stringKey) || stringKey.length() > 100) {
          throw invalid("流程变量对象字段名格式不正确");
        }
        result.put(stringKey, safeValue(item, depth + 1));
      });
      return result;
    }
    throw invalid("流程变量仅支持 JSON 基础类型、对象和数组");
  }

  private static void requireRevision(
      final WorkflowDefinitionEntity definition,
      final long expectedRevision
  ) {
    if (definition.getDraftRevision() != expectedRevision) {
      throw conflict("流程草稿已被其他操作更新，请刷新后重试");
    }
  }

  private static String checksum(final String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String normalizeStatus(final String value) {
    if (!hasText(value)) {
      return null;
    }
    String status = value.trim().toUpperCase(Locale.ROOT);
    if (!Set.of("RUNNING", "COMPLETED", "CANCELED").contains(status)) {
      throw invalid("不支持的流程状态");
    }
    return status;
  }

  private static String required(
      final String value,
      final String message,
      final int maxLength
  ) {
    if (!hasText(value)) {
      throw invalid(message);
    }
    String normalized = value.trim();
    if (normalized.length() > maxLength) {
      throw invalid(message.replace("不能为空", "不能超过 " + maxLength + " 个字符"));
    }
    return normalized;
  }

  private static String optional(
      final String value,
      final int maxLength,
      final String label
  ) {
    if (!hasText(value)) {
      return null;
    }
    String normalized = value.trim();
    if (normalized.length() > maxLength) {
      throw invalid(label + "不能超过 " + maxLength + " 个字符");
    }
    return normalized;
  }

  private static String tenantId() {
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    String tenant = context == null ? null : context.getAttribute("X-Tenant-Id");
    if (context == null || context.getScopeType() != AuthorizationScopeType.TENANT
        || !hasText(tenant)) {
      throw forbidden("请切换到组织工作空间");
    }
    return tenant.trim();
  }

  private static String userId() {
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    if (context == null || !hasText(context.getUserId())) {
      throw forbidden("无法识别当前用户");
    }
    return context.getUserId();
  }

  private static List<String> roles() {
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    if (context == null || context.getRoles() == null) {
      return List.of();
    }
    return context.getRoles().stream().filter(WorkflowCenterServiceImpl::hasText)
        .distinct().toList();
  }

  private static boolean allowed(final String authority) {
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    if (context == null) {
      return false;
    }
    if (Boolean.TRUE.equals(context.getIsAdministrator())) {
      return true;
    }
    return context.getResources() != null && context.getResources().contains(authority);
  }

  private static void requireAuthority(final String authority) {
    if (!allowed(authority)) {
      throw forbidden("当前用户没有操作权限");
    }
  }

  private Set<String> copiedUsers(
      final StartProcessCommand command,
      final String tenant,
      final String starter
  ) {
    if (command == null || command.ccUserIds() == null || command.ccUserIds().isEmpty()) {
      return Set.of();
    }
    Set<String> requested = command.ccUserIds().stream()
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(WorkflowCenterServiceImpl::hasText)
        .filter(user -> !starter.equals(user))
        .collect(Collectors.toCollection(LinkedHashSet::new));
    if (requested.size() > 50) {
      throw invalid("每个流程实例最多抄送 50 人");
    }
    Set<String> members = new LinkedHashSet<>(
        tenantService.resolveActiveMemberIds(tenant, requested)
    );
    if (!members.containsAll(requested)) {
      throw invalid("抄送人必须是当前组织的有效成员");
    }
    return requested;
  }

  private void persistWatchers(
      final WorkflowInstanceEntity instance,
      final Set<String> watchers
  ) {
    Instant now = Instant.now();
    for (String watcher : watchers) {
      WorkflowInstanceWatcherEntity entity = new WorkflowInstanceWatcherEntity();
      entity.setId(UUID.randomUUID().toString());
      entity.setTenantId(instance.getTenantId());
      entity.setProcessInstanceId(instance.getProcessInstanceId());
      entity.setUserId(watcher);
      entity.setCreatedAt(now);
      entityManager.persist(entity);
    }
  }

  private void enqueueCopiedNotifications(
      final WorkflowInstanceEntity instance,
      final Set<String> watchers
  ) {
    watchers.forEach(user -> enqueueNotification(
        instance.getTenantId(),
        "instance:" + instance.getProcessInstanceId() + ":copied:" + user,
        user,
        "流程事项已抄送给你",
        instance.getTitle() + " · " + instance.getDefinitionName(),
        "/workflow/instances"
    ));
  }

  private void enqueueTaskNotifications(final WorkflowInstanceEntity instance) {
    List<Task> tasks = taskService.createTaskQuery()
        .processInstanceId(instance.getProcessInstanceId())
        .taskTenantId(instance.getTenantId())
        .active()
        .list();
    Map<String, Set<String>> taskRecipients = new LinkedHashMap<>();
    Set<String> allRecipients = new LinkedHashSet<>();
    Set<String> candidateRoles = new LinkedHashSet<>();
    for (Task task : tasks) {
      Set<String> recipients = new LinkedHashSet<>();
      if (hasText(task.getAssignee())) {
        recipients.add(task.getAssignee());
      }
      taskService.getIdentityLinksForTask(task.getId()).stream()
          .filter(link -> "candidate".equals(link.getType())).forEach(link -> {
            if (hasText(link.getUserId())) recipients.add(link.getUserId());
            if (hasText(link.getGroupId())) candidateRoles.add(link.getGroupId());
          });
      taskRecipients.put(task.getId(), recipients);
      allRecipients.addAll(recipients);
    }
    // This also covers legacy/advanced expressions: evaluate in Flowable, then validate before
    // committing the engine transaction or creating any notification for an out-of-tenant user.
    List<String> requested = List.copyOf(allRecipients);
    for (int offset = 0; offset < requested.size(); offset += 50) {
      List<String> batch = requested.subList(offset, Math.min(offset + 50, requested.size()));
      if (!new LinkedHashSet<>(tenantService.resolveActiveMemberIds(instance.getTenantId(), batch)).containsAll(batch)) {
        throw invalid("任务办理人和候选用户必须是当前组织的有效成员");
      }
    }
    validateRoleAuthorities(instance.getTenantId(), candidateRoles);
    for (Task task : tasks) {
      Set<String> recipients = taskRecipients.get(task.getId());
      recipients.forEach(user -> enqueueNotification(
          instance.getTenantId(),
          "task:" + task.getId() + ":created:" + user,
          user,
          "你有新的流程待办",
          instance.getTitle() + " · " + task.getName(),
          "/workflow/tasks"
      ));
    }
  }

  private void enqueueInstanceCompleted(final WorkflowInstanceEntity instance) {
    enqueueNotification(
        instance.getTenantId(),
        "instance:" + instance.getProcessInstanceId() + ":completed:"
            + instance.getStarterId(),
        instance.getStarterId(),
        "流程事项已完成",
        instance.getTitle() + " · " + instance.getDefinitionName(),
        "/workflow/instances"
    );
  }

  private void enqueueNotification(
      final String tenant,
      final String eventKey,
      final String targetUser,
      final String title,
      final String content,
      final String link
  ) {
    Long existing = entityManager.createQuery(
            "select count(o) from WorkflowNotificationOutboxEntity o where o.eventKey = :key",
            Long.class
        ).setParameter("key", eventKey)
        .getSingleResult();
    if (existing > 0L) {
      return;
    }
    Instant now = Instant.now();
    WorkflowNotificationOutboxEntity item = new WorkflowNotificationOutboxEntity();
    item.setId(UUID.randomUUID().toString());
    item.setTenantId(tenant);
    item.setEventKey(eventKey);
    item.setTargetUserId(targetUser);
    item.setTitle(title);
    item.setContent(content);
    item.setLinkUrl(link);
    item.setStatus("PENDING");
    item.setAttempts(0);
    item.setNextAttemptAt(now);
    item.setCreatedAt(now);
    entityManager.persist(item);
  }

  private void requireInstanceVisibility(final WorkflowInstanceEntity instance) {
    String user = userId();
    if (allowed(WorkflowResourceCodes.INSTANCES_VIEW)
        || user.equals(instance.getStarterId())) {
      return;
    }
    boolean involved = historyService.createHistoricTaskInstanceQuery()
        .processInstanceId(instance.getProcessInstanceId())
        .taskInvolvedUser(user)
        .count() > 0;
    if (!involved) {
      involved = entityManager.createQuery(
              "select count(w) from WorkflowInstanceWatcherEntity w"
                  + " where w.tenantId = :tenant and w.processInstanceId = :instance"
                  + " and w.userId = :user",
              Long.class
          ).setParameter("tenant", instance.getTenantId())
          .setParameter("instance", instance.getProcessInstanceId())
          .setParameter("user", user)
          .getSingleResult() > 0L;
    }
    if (!involved && "RUNNING".equals(instance.getStatus())) {
      involved = taskService.createTaskQuery()
          .processInstanceId(instance.getProcessInstanceId())
          .taskCandidateOrAssigned(user)
          .count() > 0;
      List<String> groups = roles();
      if (!involved && !groups.isEmpty()) {
        involved = taskService.createTaskQuery()
            .processInstanceId(instance.getProcessInstanceId())
            .taskCandidateGroupIn(groups)
            .count() > 0;
      }
    }
    if (!involved) {
      throw forbidden("当前用户不能查看该流程实例");
    }
  }

  private static <T> String value(final T source, final Function<T, String> getter) {
    return source == null ? null : getter.apply(source);
  }

  private static Instant instant(final Date value) {
    return value == null ? null : value.toInstant();
  }

  private static String escapeLike(final String value) {
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  private static boolean hasText(final String value) {
    return value != null && !value.isBlank();
  }

  private static WorkflowException invalid(final String message) {
    return new WorkflowException(Code.VALIDATION, message);
  }

  private static WorkflowException notFound(final String message) {
    return new WorkflowException(Code.NOT_FOUND, message);
  }

  private static WorkflowException conflict(final String message) {
    return new WorkflowException(Code.CONFLICT, message);
  }

  private static WorkflowException forbidden(final String message) {
    return new WorkflowException(Code.FORBIDDEN, message);
  }

  private record PageBounds(int page, int size, int offset) {

    static PageBounds of(final int requestedPage, final int requestedSize) {
      int page = Math.max(0, requestedPage);
      int size = Math.max(1, Math.min(MAX_PAGE_SIZE, requestedSize));
      int maxPage = Integer.MAX_VALUE / size;
      page = Math.min(page, maxPage);
      return new PageBounds(page, size, page * size);
    }
  }

  private record Search(String value, String pattern) {

    static Search of(final String keyword) {
      String value = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
      if (value.length() > 100) {
        throw invalid("搜索词不能超过 100 个字符");
      }
      return new Search(value, "%" + escapeLike(value) + "%");
    }
  }
}
