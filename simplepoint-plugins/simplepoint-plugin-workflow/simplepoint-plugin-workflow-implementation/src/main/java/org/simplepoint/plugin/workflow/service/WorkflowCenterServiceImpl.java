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
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
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
import org.simplepoint.plugin.rbac.tenant.api.service.TenantService;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.WorkflowException.Code;
import org.simplepoint.plugin.workflow.api.WorkflowModelingException;
import org.simplepoint.plugin.workflow.api.constants.WorkflowResourceCodes;
import org.simplepoint.plugin.workflow.api.constants.WorkflowVariableNames;
import org.simplepoint.plugin.workflow.api.model.WorkflowAuditTrail;
import org.simplepoint.plugin.workflow.api.model.WorkflowFormContext;
import org.simplepoint.plugin.workflow.api.model.WorkflowFormContract;
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
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.RuntimeForm;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.RuntimePermissions;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.StartProcessCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.TaskSummary;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.UpdateDraftCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.VersionDetail;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.VersionSummary;
import org.simplepoint.plugin.workflow.api.model.WorkflowProgress;
import org.simplepoint.plugin.workflow.api.model.WorkflowProgress.NodeExecution;
import org.simplepoint.plugin.workflow.api.model.WorkflowResubmission;
import org.simplepoint.plugin.workflow.api.service.WorkflowCenterService;
import org.simplepoint.plugin.workflow.api.service.WorkflowFormProvider;
import org.simplepoint.plugin.workflow.entity.WorkflowActionEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowBusinessLinkEntity;
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
  private final WorkflowOperationJournal operations;
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
      final List<WorkflowFormProvider> formProviders) {
    this.entityManager = entityManager;
    this.repositoryService = repositoryService;
    this.runtimeService = runtimeService;
    this.taskService = taskService;
    this.historyService = historyService;
    this.bpmnValidator = bpmnValidator;
    this.objectMapper = objectMapper;
    this.tenantService = tenantService;
    this.formContracts = new WorkflowFormContracts(objectMapper);
    this.operations = new WorkflowOperationJournal(entityManager, objectMapper);
    this.formProviders =
        formProviders.stream()
            .collect(
                Collectors.toUnmodifiableMap(WorkflowFormProvider::provider, Function.identity()));
  }

  @Override
  public PageResult<DefinitionSummary> definitions(
      final String keyword, final int page, final int size) {
    final String tenant = tenantId();
    final PageBounds bounds = PageBounds.of(page, size);
    Search search = Search.of(keyword);
    String where =
        "d.tenantId = :tenant and (:search = '' or lower(d.key) like :pattern"
            + " escape '!' or lower(d.name) like :pattern escape '!' or"
            + " lower(coalesce(d.description, '')) like :pattern escape '!')";
    List<WorkflowDefinitionEntity> rows =
        entityManager
            .createQuery(
                "select d from WorkflowDefinitionEntity d where "
                    + where
                    + " order by d.createdAt desc, d.id desc",
                WorkflowDefinitionEntity.class)
            .setParameter("tenant", tenant)
            .setParameter("search", search.value())
            .setParameter("pattern", search.pattern())
            .setFirstResult(bounds.offset())
            .setMaxResults(bounds.size())
            .getResultList();
    long total =
        entityManager
            .createQuery(
                "select count(d) from WorkflowDefinitionEntity d where " + where, Long.class)
            .setParameter("tenant", tenant)
            .setParameter("search", search.value())
            .setParameter("pattern", search.pattern())
            .getSingleResult();
    return new PageResult<>(
        rows.stream().map(this::definitionSummary).toList(), bounds.page(), bounds.size(), total);
  }

  @Override
  public PageResult<DefinitionSummary> queryDefinitions(
      Map<String, String> filters, org.springframework.data.domain.Pageable page) {
    requireAuthority(WorkflowResourceCodes.DEFINITIONS_VIEW);
    final String tenant = tenantId();
    Map<String, String> columns = new LinkedHashMap<>(filters == null ? Map.of() : filters);
    Search keyword = Search.of(columns.remove("keyword"));
    return WorkflowReadPages.query(
        entityManager,
        WorkflowDefinitionEntity.class,
        DefinitionSummary.class,
        columns,
        page,
        "updatedAt",
        Set.of("name", "key", "category", "description"),
        (cb, query, root) -> {
          var own = cb.equal(root.get("tenantId"), tenant);
          if (keyword.value().isBlank()) {
            return own;
          }
          return cb.and(
              own,
              cb.or(
                  cb.like(cb.lower(root.get("name")), keyword.pattern(), '!'),
                  cb.like(cb.lower(root.get("key")), keyword.pattern(), '!'),
                  cb.like(cb.lower(root.get("description")), keyword.pattern(), '!')));
        },
        this::definitionSummary);
  }

  @Override
  public Map<String, Object> definitionsSchema() {
    requireAuthority(WorkflowResourceCodes.DEFINITIONS_VIEW);
    tenantId();
    return WorkflowReadPages.schema(
        DefinitionSummary.class,
        "workflow.definition",
        Map.of("status", List.of("DRAFT", "PUBLISHED")));
  }

  @Override
  public DefinitionPermissions definitionPermissions() {
    tenantId();
    return new DefinitionPermissions(
        allowed(WorkflowResourceCodes.DEFINITIONS_CREATE),
        allowed(WorkflowResourceCodes.DEFINITIONS_EDIT),
        allowed(WorkflowResourceCodes.DEFINITIONS_PUBLISH),
        allowed(WorkflowResourceCodes.DEFINITIONS_COPY_VERSION),
        allowed(WorkflowResourceCodes.INSTANCES_START));
  }

  @Override
  public RuntimePermissions runtimePermissions() {
    tenantId();
    return new RuntimePermissions(
        allowed(WorkflowResourceCodes.TASKS_CLAIM),
        allowed(WorkflowResourceCodes.TASKS_COMPLETE),
        allowed(WorkflowResourceCodes.INSTANCES_VIEW),
        allowed(WorkflowResourceCodes.INSTANCES_CANCEL),
        allowed(WorkflowResourceCodes.INSTANCES_START));
  }

  @Override
  @Transactional
  public DefinitionDetail createDefinition(final CreateDefinitionCommand command) {
    requireAuthority(WorkflowResourceCodes.DEFINITIONS_CREATE);
    final String tenant = tenantId();
    if (command == null) {
      throw invalid("请填写流程定义");
    }
    String key = required(command.key(), "流程标识不能为空", 80);
    if (!KEY.matcher(key).matches()) {
      throw invalid("流程标识必须以字母开头，只能包含字母、数字、下划线和短横线");
    }
    Long duplicates =
        entityManager
            .createQuery(
                "select count(d) from WorkflowDefinitionEntity d"
                    + " where d.tenantId = :tenant and d.key = :key",
                Long.class)
            .setParameter("tenant", tenant)
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
  public DefinitionDetail updateDraft(final String definitionId, final UpdateDraftCommand command) {
    requireAuthority(WorkflowResourceCodes.DEFINITIONS_EDIT);
    if (command == null) {
      throw invalid("请提交流程草稿");
    }
    WorkflowDefinitionEntity definition = requireDefinition(definitionId, true);
    requireRevision(definition, command.expectedRevision());
    String xml = required(command.bpmnXml(), "BPMN XML 不能为空", 2_000_000);
    // Omitted contracts preserve existing integrations for older API clients, rather than clearing
    // them.
    String contract =
        command.formContract() == null
            ? definition.getDraftFormContract()
            : formContracts.write(command.formContract());
    var validated = bpmnValidator.validate(xml, definition.getKey(), formContracts.read(contract));
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
  public VersionSummary publish(final String definitionId, final PublishDefinitionCommand command) {
    requireAuthority(WorkflowResourceCodes.DEFINITIONS_PUBLISH);
    if (command == null) {
      throw invalid("请提交发布信息");
    }
    WorkflowDefinitionEntity definition = requireDefinition(definitionId, true);
    requireRevision(definition, command.expectedRevision());
    var validated =
        bpmnValidator.validate(
            definition.getDraftXml(),
            definition.getKey(),
            formContracts.read(definition.getDraftFormContract()));
    formContracts.validateTaskBindings(
        formContracts.read(definition.getDraftFormContract()), validated.userTaskIds());
    definition.setDraftFormContract(
        formContracts.write(formContracts.read(definition.getDraftFormContract())));
    validatePublishedBindings(formContracts.read(definition.getDraftFormContract()));
    String checksum = snapshotChecksum(definition);
    var execution = BpmnExecutionCompiler.compile(definition.getDraftXml());
    if (checksum.equals(definition.getPublishedChecksum()) && definition.getLatestVersion() > 0) {
      var latest = requireVersion(definition, definition.getLatestVersion());
      if (execution.matchesDeployment(
          repositoryService.getDeploymentResourceNames(latest.getFlowableDeploymentId()))) {
        return versionSummary(latest);
      }
    }
    int nextVersion = definition.getLatestVersion() + 1;
    String comment = optional(command.comment(), 500, "发布说明");
    try {
      var deploymentBuilder =
          repositoryService
              .createDeployment()
              .tenantId(definition.getTenantId())
              .name(definition.getName() + " v" + nextVersion)
              .category(definition.getCategory())
              .addString(definition.getKey() + ".bpmn20.xml", execution.xml());
      if (execution.transformed()) {
        deploymentBuilder.addString(BpmnExecutionCompiler.MANIFEST_RESOURCE, execution.manifest());
      }
      if (execution.diagramData()) {
        deploymentBuilder.addString(BpmnExecutionCompiler.DIAGRAM_RESOURCE, execution.manifest());
      }
      Deployment deployment = deploymentBuilder.deploy();
      List<ProcessDefinition> deployed =
          repositoryService.createProcessDefinitionQuery().deploymentId(deployment.getId()).list();
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
      version.setFormContract(
          formContracts.write(formContracts.read(definition.getDraftFormContract())));
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
      var startBinding = formContracts.read(version.getFormContract()).startBinding();
      if (startBinding != null) {
        var index = new org.simplepoint.plugin.workflow.entity.WorkflowFormBindingEntity();
        index.setId(version.getId());
        index.setTenantId(version.getTenantId());
        index.setDefinitionId(definitionId);
        index.setDefinitionVersion(nextVersion);
        index.setProvider(startBinding.provider());
        index.setFormId(startBinding.formId());
        index.setFormVersion(startBinding.version());
        entityManager.persist(index);
      }
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
    return new VersionDetail(
        versionSummary(item),
        item.getBpmnXml(),
        item.getDefinitionName(),
        item.getDefinitionCategory(),
        item.getDefinitionDescription(),
        formContracts.read(item.getFormContract()));
  }

  @Override
  @Transactional
  public DefinitionDetail copyVersionToDraft(
      final String definitionId, final int version, final long expectedRevision) {
    requireAuthority(WorkflowResourceCodes.DEFINITIONS_COPY_VERSION);
    WorkflowDefinitionEntity definition = requireDefinition(definitionId, true);
    requireRevision(definition, expectedRevision);
    WorkflowDefinitionVersionEntity source = requireVersion(definition, version);
    definition.setDraftXml(source.getBpmnXml());
    definition.setDraftFormContract(
        formContracts.write(formContracts.read(source.getFormContract())));
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
      final String definitionId, final StartProcessCommand command) {
    requireAuthority(WorkflowResourceCodes.INSTANCES_START);
    return operations.execute(
        tenantId(),
        userId(),
        "START",
        definitionId,
        command == null ? null : command.idempotencyKey(),
        command,
        () -> requireDefinition(definitionId, true),
        () -> startInternal(definitionId, command, null));
  }

  private ProcessInstanceSummary startInternal(
      String definitionId, StartProcessCommand command, String previousId) {
    WorkflowDefinitionEntity definition = requireDefinition(definitionId, true);
    if (definition.getLatestVersion() < 1) {
      throw conflict("流程尚未发布");
    }
    int requestedVersion =
        command == null || command.version() == null
            ? definition.getLatestVersion()
            : command.version();
    WorkflowDefinitionVersionEntity version = requireVersion(definition, requestedVersion);
    requireExecutableVersion(version);
    final String businessKey = command == null ? null : optional(command.businessKey(), 160, "业务标识");
    String versionName =
        version.getDefinitionName() == null ? definition.getName() : version.getDefinitionName();
    String title = command == null ? versionName : optional(command.title(), 240, "事项标题");
    if (title == null) {
      title = versionName;
    }
    Map<String, Object> variables = safeVariables(command == null ? null : command.variables());
    WorkflowFormContract contract = formContracts.read(version.getFormContract());
    variables = formContracts.submission(contract, variables, Map.of(), "start");
    validateDirectoryVariables(contract, variables, definition.getTenantId());
    validateExternalForm(
        contract.startBinding(),
        "start",
        null,
        variables,
        new WorkflowFormContext(
            tenantId(),
            userId(),
            definitionId,
            requestedVersion,
            null,
            businessKey,
            formAuthority()));
    String businessLink = businessLink(contract.startBinding(), businessKey);
    if (businessLink != null
        && entityManager.find(WorkflowBusinessLinkEntity.class, businessLink) != null) {
      throw conflict("此业务提交已发起流程，请使用原提交操作重试或查看已关联事项");
    }
    String starter = userId();
    Set<String> watchers = copiedUsers(command, definition.getTenantId(), starter);
    variables.put("initiator", starter);
    variables.put("workflowDefinitionId", definition.getId());
    variables.put("workflowVersion", requestedVersion);
    variables.put("workflowTitle", title);
    try {
      ProcessInstance process =
          runtimeService.startProcessInstanceById(
              version.getFlowableProcessDefinitionId(),
              businessKey,
              formContracts.engineVariables(contract, variables));
      WorkflowInstanceEntity instance = new WorkflowInstanceEntity();
      instance.setProcessInstanceId(process.getId());
      instance.setTenantId(definition.getTenantId());
      instance.setDefinitionId(definition.getId());
      instance.setDefinitionVersionId(version.getId());
      instance.setPreviousInstanceId(previousId);
      instance.setDefinitionKey(definition.getKey());
      instance.setDefinitionName(versionName);
      instance.setDefinitionVersion(version.getVersion());
      instance.setBusinessKey(businessKey);
      instance.setTitle(title);
      instance.setStarterId(starter);
      instance.setStartedAt(Instant.now());
      boolean running =
          runtimeService
                  .createProcessInstanceQuery()
                  .processInstanceId(process.getId())
                  .singleResult()
              != null;
      instance.setStatus(running ? "RUNNING" : "COMPLETED");
      if (!running) {
        instance.setEndedAt(Instant.now());
      }
      entityManager.persist(instance);
      if (businessLink != null) {
        var link = new WorkflowBusinessLinkEntity();
        link.setId(businessLink);
        link.setTenantId(tenantId());
        link.setProvider(contract.startBinding().provider());
        link.setBusinessKey(businessKey);
        link.setProcessInstanceId(process.getId());
        entityManager.persist(link);
      }
      recordAction(instance, null, previousId == null ? "STARTED" : "RESUBMITTED", null);
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
  public WorkflowResubmission resubmissionForm(String processInstanceId) {
    WorkflowInstanceEntity source = requireInstance(processInstanceId);
    WorkflowDefinitionVersionEntity version = requireResubmission(source);
    WorkflowFormContract contract = formContracts.read(version.getFormContract());
    HistoricProcessInstance history =
        historyService
            .createHistoricProcessInstanceQuery()
            .processInstanceId(source.getProcessInstanceId())
            .processInstanceTenantId(source.getTenantId())
            .includeProcessVariables()
            .singleResult();
    if (history == null || history.getEndTime() == null) {
      throw conflict("原流程历史尚不可用，请刷新后重试");
    }
    RuntimeForm form =
        runtimeForm(
            version.getVersion(),
            contract,
            contract.startBinding(),
            history.getProcessVariables() == null ? Map.of() : history.getProcessVariables(),
            "start",
            source.getDefinitionId(),
            source.getProcessInstanceId(),
            source.getBusinessKey());
    List<String> watchers =
        entityManager
            .createQuery(
                "select w.userId from WorkflowInstanceWatcherEntity w where w.tenantId = :tenant"
                    + " and w.processInstanceId = :instance order by w.userId",
                String.class)
            .setParameter("tenant", source.getTenantId())
            .setParameter("instance", source.getProcessInstanceId())
            .setMaxResults(50)
            .getResultList();
    return new WorkflowResubmission(
        new WorkflowMatterQueries(entityManager)
            .findVisibleProjection(source.getProcessInstanceId()),
        form,
        watchers);
  }

  @Override
  @Transactional
  public ProcessInstanceSummary resubmit(String processInstanceId, StartProcessCommand command) {
    requireAuthority(WorkflowResourceCodes.INSTANCES_START);
    return operations.execute(
        tenantId(),
        userId(),
        "RESUBMIT",
        processInstanceId,
        command == null ? null : command.idempotencyKey(),
        command,
        () -> requireLockedInstance(processInstanceId),
        () -> resubmitInternal(processInstanceId, command));
  }

  private ProcessInstanceSummary resubmitInternal(
      String processInstanceId, StartProcessCommand command) {
    WorkflowInstanceEntity source = requireLockedInstance(processInstanceId);
    requireResubmission(source);
    if (command == null
        || command.version() == null
        || command.version() != source.getDefinitionVersion()) {
      throw conflict("重新发起必须使用原事项的已发布版本，请刷新表单");
    }
    return startInternal(source.getDefinitionId(), command, source.getProcessInstanceId());
  }

  private WorkflowDefinitionVersionEntity requireResubmission(WorkflowInstanceEntity source) {
    requireAuthority(WorkflowResourceCodes.INSTANCES_START);
    if (!WorkflowAccess.canViewPersonalMatters() || !userId().equals(source.getStarterId())) {
      throw forbidden("只有原发起人可以修改并重新发起事项");
    }
    String state = effectiveInstanceSummary(source).status();
    if (!Set.of("REJECTED", "WITHDRAWN", "CANCELED").contains(state)) {
      throw conflict("只有驳回、撤回或取消的事项可以重新发起");
    }
    if (historyService
            .createHistoricProcessInstanceQuery()
            .processInstanceId(source.getProcessInstanceId())
            .processInstanceTenantId(source.getTenantId())
            .finished()
            .count()
        != 1) {
      throw conflict("原流程尚未结束，不能重新发起");
    }
    List<String> successors =
        entityManager
            .createQuery(
                "select i.processInstanceId from WorkflowInstanceEntity i where i.tenantId ="
                    + " :tenant and i.previousInstanceId = :source",
                String.class)
            .setParameter("tenant", source.getTenantId())
            .setParameter("source", source.getProcessInstanceId())
            .setMaxResults(1)
            .getResultList();
    if (!successors.isEmpty()) {
      throw conflict("该事项已经重新发起，请查看关联的新事项");
    }
    WorkflowDefinitionVersionEntity version =
        requireVersion(
            requireDefinition(source.getDefinitionId(), false), source.getDefinitionVersion());
    requireExecutableVersion(version);
    if (repositoryService
            .createProcessDefinitionQuery()
            .processDefinitionId(version.getFlowableProcessDefinitionId())
            .active()
            .count()
        != 1) {
      throw conflict("原流程版本当前不可用，不能重新发起");
    }
    return version;
  }

  @Override
  public PageResult<org.simplepoint.plugin.workflow.api.model.WorkflowModels.BoundDefinition>
      boundDefinitions(
          String provider, String formId, int formVersion, String keyword, int page, int size) {
    requireAuthority(WorkflowResourceCodes.INSTANCES_START);
    final String tenant = tenantId();
    provider = required(provider, "表单提供方不能为空", 80);
    formId = required(formId, "表单标识不能为空", 160);
    if (formVersion < 1) {
      throw invalid("表单版本无效");
    }
    var bounds = PageBounds.of(page, size);
    String search =
        hasText(keyword) ? "%" + escapeLike(required(keyword.trim(), "搜索条件无效", 160)) + "%" : "%";
    String from =
        " from WorkflowDefinitionEntity definition, WorkflowFormBindingEntity binding,"
            + " WorkflowDefinitionVersionEntity version where definition.tenantId = :tenant and"
            + " binding.tenantId = :tenant and binding.definitionId = definition.id and"
            + " binding.definitionVersion = definition.latestVersion and binding.id = version.id"
            + " and definition.status = 'PUBLISHED' and binding.provider = :provider and"
            + " binding.formId = :form and binding.formVersion = :version and"
            + " version.definitionName like :search escape '!'";
    var query =
        entityManager.createQuery(
            "select version" + from + " order by version.publishedAt desc, version.id",
            WorkflowDefinitionVersionEntity.class);
    var count = entityManager.createQuery("select count(definition)" + from, Long.class);
    for (var target : List.of(query, count)) {
      target
          .setParameter("tenant", tenant)
          .setParameter("provider", provider)
          .setParameter("form", formId)
          .setParameter("version", formVersion)
          .setParameter("search", search);
    }
    var rows =
        query.setFirstResult(bounds.offset()).setMaxResults(bounds.size()).getResultList().stream()
            .map(
                definition ->
                    new org.simplepoint.plugin.workflow.api.model.WorkflowModels.BoundDefinition(
                        definition.getDefinitionId(),
                        definition.getDefinitionName(),
                        definition.getVersion(),
                        definition.getDefinitionDescription()))
            .toList();
    return new PageResult<>(rows, bounds.page(), bounds.size(), count.getSingleResult());
  }

  @Override
  public RuntimeForm startForm(final String definitionId, final Integer requestedVersion) {
    requireAuthority(WorkflowResourceCodes.INSTANCES_START);
    WorkflowDefinitionEntity definition = requireDefinition(definitionId, false);
    WorkflowDefinitionVersionEntity version =
        requireVersion(
            definition,
            requestedVersion == null ? definition.getLatestVersion() : requestedVersion);
    requireExecutableVersion(version);
    WorkflowFormContract contract = formContracts.read(version.getFormContract());
    return runtimeForm(
        version.getVersion(),
        contract,
        contract.startBinding(),
        Map.of(),
        "start",
        definitionId,
        null,
        null);
  }

  @Override
  public RuntimeForm validateStartForm(
      String definitionId, int version, Map<String, Object> variables, List<String> ccUserIds) {
    requireRuntimeView();
    RuntimeForm form = startForm(definitionId, version);
    var validated =
        formContracts.submission(form.formContract(), safeVariables(variables), Map.of(), "start");
    validateDirectoryVariables(form.formContract(), validated, tenantId());
    copiedUsers(
        new StartProcessCommand(version, null, null, Map.of(), ccUserIds), tenantId(), userId());
    return new RuntimeForm(
        form.definitionVersion(), form.formContract(), form.binding(), validated);
  }

  @Override
  public RuntimeForm taskForm(final String taskId) {
    requireAuthority(WorkflowResourceCodes.TASKS_COMPLETE);
    Task task = requireTask(taskId, tenantId());
    if (hasText(task.getAssignee())
        ? !userId().equals(task.getAssignee())
        : !isCandidate(taskId, userId(), roles())) {
      throw forbidden("仅当前办理人或候选人可以读取任务表单");
    }
    WorkflowInstanceEntity instance = requireInstance(task.getProcessInstanceId());
    WorkflowDefinitionVersionEntity version =
        requireVersion(
            requireDefinition(instance.getDefinitionId(), false), instance.getDefinitionVersion());
    WorkflowFormContract contract = formContracts.read(version.getFormContract());
    return runtimeForm(
        version.getVersion(),
        contract,
        contract.taskBindings().get(task.getTaskDefinitionKey()),
        runtimeService.getVariables(instance.getProcessInstanceId()),
        "task",
        instance.getDefinitionId(),
        instance.getProcessInstanceId(),
        instance.getBusinessKey());
  }

  @Override
  public PageResult<TaskSummary> pendingTasks(
      final String keyword, final int page, final int size) {
    final String tenant = tenantId();
    String user = userId();
    final PageBounds bounds = PageBounds.of(page, size);
    List<String> groups = roles();
    var query =
        taskService
            .createTaskQuery()
            .taskTenantId(tenant)
            .active()
            .or()
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
    List<Task> tasks =
        query.orderByTaskCreateTime().desc().listPage(bounds.offset(), bounds.size());
    Map<String, WorkflowInstanceEntity> instances =
        instancesByIds(tasks.stream().map(Task::getProcessInstanceId).toList());
    return new PageResult<>(
        tasks.stream()
            .map(
                task -> taskSummary(task, instances.get(task.getProcessInstanceId()), user, groups))
            .toList(),
        bounds.page(),
        bounds.size(),
        total);
  }

  @Override
  public PageResult<TaskSummary> completedTasks(
      final String keyword, final int page, final int size) {
    final String tenant = tenantId();
    String user = userId();
    PageBounds bounds = PageBounds.of(page, size);
    var query =
        historyService
            .createHistoricTaskInstanceQuery()
            .taskTenantId(tenant)
            .taskAssignee(user)
            .finished();
    if (hasText(keyword)) {
      query.taskNameLikeIgnoreCase("%" + escapeLike(keyword.trim()) + "%");
    }
    long total = query.count();
    List<HistoricTaskInstance> tasks =
        query.orderByHistoricTaskInstanceEndTime().desc().listPage(bounds.offset(), bounds.size());
    Map<String, WorkflowInstanceEntity> instances =
        instancesByIds(tasks.stream().map(HistoricTaskInstance::getProcessInstanceId).toList());
    return new PageResult<>(
        tasks.stream()
            .map(task -> taskSummary(task, instances.get(task.getProcessInstanceId())))
            .toList(),
        bounds.page(),
        bounds.size(),
        total);
  }

  @Override
  @Transactional
  public TaskSummary claim(final String taskId) {
    requireAuthority(WorkflowResourceCodes.TASKS_CLAIM);
    final String tenant = tenantId();
    String user = userId();
    List<String> groups = roles();
    WorkflowInstanceEntity instance =
        requireLockedInstance(requireTask(taskId, tenant).getProcessInstanceId());
    requireRunning(instance);
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
      recordAction(instance, task, "CLAIMED", null);
    }
    return taskSummary(task, instance, user, groups);
  }

  @Override
  @Transactional
  public ProcessInstanceSummary complete(final String taskId, final CompleteTaskCommand command) {
    requireAuthority(WorkflowResourceCodes.TASKS_COMPLETE);
    return operations.execute(
        tenantId(),
        userId(),
        "COMPLETE",
        taskId,
        command == null ? null : command.idempotencyKey(),
        command,
        () -> lockTaskInstance(taskId),
        () -> completeInternal(taskId, command));
  }

  private ProcessInstanceSummary completeInternal(String taskId, CompleteTaskCommand command) {
    final String tenant = tenantId();
    String user = userId();
    List<String> groups = roles();
    WorkflowInstanceEntity instance =
        requireLockedInstance(requireTask(taskId, tenant).getProcessInstanceId());
    requireRunning(instance);
    Task task = claimForDecision(taskId, tenant, user, groups);
    String comment = command == null ? null : optional(command.comment(), 2000, "办理意见");
    if (comment != null) {
      var recorded = taskService.addComment(taskId, task.getProcessInstanceId(), comment);
      recorded.setUserId(user);
      taskService.saveComment(recorded);
    }
    try {
      WorkflowDefinitionVersionEntity version =
          requireVersion(
              requireDefinition(instance.getDefinitionId(), false),
              instance.getDefinitionVersion());
      WorkflowFormContract contract = formContracts.read(version.getFormContract());
      Map<String, Object> variables =
          formContracts.submission(
              contract,
              safeVariables(command == null ? null : command.variables()),
              runtimeService.getVariables(instance.getProcessInstanceId()),
              "task");
      if (contract.startBinding() != null) {
        var provider = formProviders.get(contract.startBinding().provider());
        if (provider == null) {
          throw conflict("此流程绑定的表单提供方尚未接入");
        }
        if (provider.protectsMappedVariables()) {
          formContracts.protectMappedVariables(
              contract, variables, runtimeService.getVariables(instance.getProcessInstanceId()));
        }
      }
      validateDirectoryVariables(contract, variables, tenant);
      var taskBinding = contract.taskBindings().get(task.getTaskDefinitionKey());
      if (taskBinding == null
          && contract.startBinding() != null
          && formProviders.get(contract.startBinding().provider()).protectsMappedVariables()) {
        taskBinding = contract.startBinding();
      }
      validateExternalForm(
          taskBinding,
          "task",
          task.getTaskDefinitionKey(),
          variables,
          new WorkflowFormContext(
              tenantId(),
              userId(),
              instance.getDefinitionId(),
              instance.getDefinitionVersion(),
              instance.getProcessInstanceId(),
              instance.getBusinessKey(),
              formAuthority()));
      taskService.complete(taskId, user, formContracts.engineVariables(contract, variables));
      recordAction(instance, task, "APPROVED", comment);
    } catch (FlowableException exception) {
      throw new WorkflowException(Code.ENGINE_FAILURE, "任务提交失败", exception);
    }
    ProcessInstance running =
        runtimeService
            .createProcessInstanceQuery()
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
  @Transactional
  public ProcessInstanceSummary reject(final String taskId, final String reason) {
    return reject(taskId, reason, null);
  }

  @Override
  @Transactional
  public ProcessInstanceSummary reject(String taskId, String reason, String idempotencyKey) {
    requireAuthority(WorkflowResourceCodes.TASKS_COMPLETE);
    return operations.execute(
        tenantId(),
        userId(),
        "REJECT",
        taskId,
        idempotencyKey,
        java.util.Collections.singletonMap("reason", reason),
        () -> lockTaskInstance(taskId),
        () -> rejectInternal(taskId, reason));
  }

  private ProcessInstanceSummary rejectInternal(String taskId, String reason) {
    requireAuthority(WorkflowResourceCodes.TASKS_COMPLETE);
    String normalizedReason = required(reason, "请填写驳回原因", 500);
    final String tenant = tenantId();
    WorkflowInstanceEntity instance =
        requireLockedInstance(requireTask(taskId, tenant).getProcessInstanceId());
    requireRunning(instance);
    Task task = claimForDecision(taskId, tenant, userId(), roles());
    var comment = taskService.addComment(taskId, instance.getProcessInstanceId(), normalizedReason);
    comment.setUserId(userId());
    taskService.saveComment(comment);
    return terminate(instance, "REJECTED", normalizedReason, task);
  }

  @Override
  @Transactional
  public ProcessInstanceSummary withdraw(final String processInstanceId, final String reason) {
    return withdraw(processInstanceId, reason, null);
  }

  @Override
  @Transactional
  public ProcessInstanceSummary withdraw(
      String processInstanceId, String reason, String idempotencyKey) {
    if (!WorkflowAccess.canViewPersonalMatters()) {
      requireAuthority(WorkflowResourceCodes.INSTANCES_STARTED_VIEW);
    }
    return operations.execute(
        tenantId(),
        userId(),
        "WITHDRAW",
        processInstanceId,
        idempotencyKey,
        java.util.Collections.singletonMap("reason", reason),
        () -> requireLockedInstance(processInstanceId),
        () -> withdrawInternal(processInstanceId, reason));
  }

  private ProcessInstanceSummary withdrawInternal(String processInstanceId, String reason) {
    if (!WorkflowAccess.canViewPersonalMatters()) {
      requireAuthority(WorkflowResourceCodes.INSTANCES_STARTED_VIEW);
    }
    WorkflowInstanceEntity instance = requireLockedInstance(processInstanceId);
    if (!userId().equals(instance.getStarterId())) {
      throw forbidden("仅流程发起人可以撤回流程");
    }
    return terminate(instance, "WITHDRAWN", required(reason, "请填写撤回原因", 500), null);
  }

  private void lockTaskInstance(String taskId) {
    var task =
        historyService
            .createHistoricTaskInstanceQuery()
            .taskId(taskId)
            .taskTenantId(tenantId())
            .singleResult();
    if (task == null) {
      throw new WorkflowException(Code.NOT_FOUND, "流程任务不存在");
    }
    requireLockedInstance(task.getProcessInstanceId());
  }

  private Task claimForDecision(
      final String taskId, final String tenant, final String user, final List<String> groups) {
    Task task = requireTask(taskId, tenant);
    if (!hasText(task.getAssignee())) {
      if (!isCandidate(taskId, user, groups)) {
        throw forbidden("当前用户不在任务候选范围内");
      }
      taskService.claim(taskId, user);
    } else if (!user.equals(task.getAssignee())) {
      throw forbidden("仅当前办理人可以处理任务");
    }
    return task;
  }

  @Override
  public PageResult<ProcessInstanceSummary> startedInstances(
      final String keyword, final int page, final int size) {
    requireAuthority(WorkflowResourceCodes.INSTANCES_STARTED_VIEW);
    return instancePage(keyword, null, userId(), page, size);
  }

  @Override
  public PageResult<ProcessInstanceSummary> copiedInstances(
      final String keyword, final int page, final int size) {
    requireAuthority(WorkflowResourceCodes.INSTANCES_STARTED_VIEW);
    final String tenant = tenantId();
    String user = userId();
    final PageBounds bounds = PageBounds.of(page, size);
    Search search = Search.of(keyword);
    String where =
        "w.tenantId = :tenant and w.userId = :user"
            + " and w.processInstanceId = i.processInstanceId"
            + " and (:search = '' or lower(i.title) like :pattern escape '!'"
            + " or lower(i.definitionName) like :pattern escape '!'"
            + " or lower(coalesce(i.businessKey, '')) like :pattern escape '!')";
    List<WorkflowInstanceEntity> rows =
        entityManager
            .createQuery(
                "select i from WorkflowInstanceEntity i, WorkflowInstanceWatcherEntity w where "
                    + where
                    + " order by i.startedAt desc, i.processInstanceId desc",
                WorkflowInstanceEntity.class)
            .setParameter("tenant", tenant)
            .setParameter("user", user)
            .setParameter("search", search.value())
            .setParameter("pattern", search.pattern())
            .setFirstResult(bounds.offset())
            .setMaxResults(bounds.size())
            .getResultList();
    long total =
        entityManager
            .createQuery(
                "select count(i) from WorkflowInstanceEntity i, WorkflowInstanceWatcherEntity w"
                    + " where "
                    + where,
                Long.class)
            .setParameter("tenant", tenant)
            .setParameter("user", user)
            .setParameter("search", search.value())
            .setParameter("pattern", search.pattern())
            .getSingleResult();
    return new PageResult<>(
        rows.stream().map(this::instanceSummary).toList(), bounds.page(), bounds.size(), total);
  }

  @Override
  public PageResult<ProcessInstanceSummary> instances(
      final String keyword, final String status, final int page, final int size) {
    requireAuthority(WorkflowResourceCodes.INSTANCES_VIEW);
    return instancePage(keyword, status, null, page, size);
  }

  @Override
  public InstanceDiagram diagram(final String processInstanceId) {
    WorkflowInstanceEntity instance = requireInstance(processInstanceId);
    requireInstanceVisibility(instance);
    WorkflowDefinitionVersionEntity version =
        entityManager.find(
            WorkflowDefinitionVersionEntity.class, instance.getDefinitionVersionId());
    if (version == null || !instance.getTenantId().equals(version.getTenantId())) {
      throw notFound("流程版本不存在");
    }
    ProcessInstanceSummary effective = effectiveInstanceSummary(instance);
    List<String> activeIds = List.of();
    if ("RUNNING".equals(effective.status())) {
      try {
        activeIds = runtimeService.getActiveActivityIds(processInstanceId);
      } catch (FlowableObjectNotFoundException endedWhileReading) {
        // An asynchronous engine completion can commit between the historic and runtime reads.
        effective = effectiveInstanceSummary(instance);
      }
    }
    final String effectiveStatus = effective.status();
    Set<String> activeSet = Set.copyOf(activeIds);
    var activityQuery =
        historyService
            .createHistoricActivityInstanceQuery()
            .processInstanceId(processInstanceId)
            .tenantIdIn(List.of(instance.getTenantId()))
            .orderByHistoricActivityInstanceStartTime()
            .desc()
            .orderByHistoricActivityInstanceId()
            .desc();
    long totalActivities = activityQuery.count();
    // Canvas is a bounded snapshot; selecting a node pages its complete execution timeline.
    List<HistoricActivityInstance> history = activityQuery.listPage(0, 500).reversed();
    List<ActivityState> activities =
        history.stream()
            .map(
                activity ->
                    new ActivityState(
                        activity.getActivityId(),
                        activity.getActivityName(),
                        activity.getActivityType(),
                        activityStatus(activity, activeSet, effectiveStatus),
                        instant(activity.getStartTime()),
                        instant(activity.getEndTime()),
                        activity.getAssignee(),
                        activity.getId(),
                        activity.getTaskId()))
            .toList();
    return new InstanceDiagram(
        effective,
        version.getBpmnXml(),
        activities,
        activeIds,
        totalActivities,
        totalActivities > 500);
  }

  private static String activityStatus(
      final HistoricActivityInstance activity,
      final Set<String> activeIds,
      final String instanceStatus) {
    if (hasText(activity.getDeleteReason())) {
      if (!activity.getDeleteReason().equals("simplepoint:" + instanceStatus)) {
        return "CANCELED";
      }
      return switch (instanceStatus) {
        case "REJECTED", "WITHDRAWN" -> instanceStatus;
        default -> "CANCELED";
      };
    }
    if (activity.getEndTime() != null) {
      return "COMPLETED";
    }
    return activeIds.contains(activity.getActivityId()) ? "ACTIVE" : "CANCELED";
  }

  @Override
  public WorkflowProgress progress(String processInstanceId) {
    requireRuntimeView();
    var diagram = diagram(processInstanceId); // Includes tenant and participant authorization.
    return new WorkflowProgress(
        new WorkflowMatterQueries(entityManager).findVisibleProjection(processInstanceId), diagram);
  }

  @Override
  public PageResult<NodeExecution> nodeHistory(
      String processInstanceId, String activityId, int page, int size, boolean activeOnly) {
    requireRuntimeView();
    var instance = requireInstance(processInstanceId);
    requireInstanceVisibility(instance);
    return new WorkflowRuntimeDetails(entityManager, historyService)
        .node(instance, activityId, page, size, activeOnly);
  }

  @Override
  public WorkflowAuditTrail auditTrail(String processInstanceId, int page, int size) {
    requireRuntimeView();
    var instance = requireInstance(processInstanceId);
    requireInstanceVisibility(instance);
    return new WorkflowRuntimeDetails(entityManager, historyService).audit(instance, page, size);
  }

  private static void requireRuntimeView() {
    if (!allowed(WorkflowResourceCodes.TASKS_VIEW)
        && !allowed(WorkflowResourceCodes.INSTANCES_STARTED_VIEW)
        && !allowed(WorkflowResourceCodes.INSTANCES_VIEW)) {
      throw forbidden("当前用户没有流程查看权限");
    }
  }

  @Override
  @Transactional
  public ProcessInstanceSummary cancel(final String processInstanceId, final String reason) {
    return cancel(processInstanceId, reason, null);
  }

  @Override
  @Transactional
  public ProcessInstanceSummary cancel(
      String processInstanceId, String reason, String idempotencyKey) {
    requireAuthority(WorkflowResourceCodes.INSTANCES_CANCEL);
    return operations.execute(
        tenantId(),
        userId(),
        "CANCEL",
        processInstanceId,
        idempotencyKey,
        java.util.Collections.singletonMap("reason", reason),
        () -> requireLockedInstance(processInstanceId),
        () -> cancelInternal(processInstanceId, reason));
  }

  private ProcessInstanceSummary cancelInternal(String processInstanceId, String reason) {
    requireAuthority(WorkflowResourceCodes.INSTANCES_CANCEL);
    WorkflowInstanceEntity instance = requireLockedInstance(processInstanceId);
    if (!allowed(WorkflowResourceCodes.INSTANCES_VIEW)
        && !userId().equals(instance.getStarterId())) {
      throw forbidden("仅流程发起人或流程管理员可以终止实例");
    }
    return terminate(instance, "CANCELED", required(reason, "请填写取消原因", 500), null);
  }

  private ProcessInstanceSummary terminate(
      final WorkflowInstanceEntity instance,
      final String outcome,
      final String reason,
      final Task decisionTask) {
    requireRunning(instance);
    ProcessInstance running =
        runtimeService
            .createProcessInstanceQuery()
            .processInstanceId(instance.getProcessInstanceId())
            .processInstanceTenantId(instance.getTenantId())
            .singleResult();
    if (running == null) {
      throw conflict("流程已经结束，请刷新后查看处理结果");
    }
    runtimeService.deleteProcessInstance(instance.getProcessInstanceId(), "simplepoint:" + outcome);
    instance.setStatus(outcome);
    instance.setCancelReason(reason);
    instance.setEndedAt(Instant.now());
    recordAction(instance, decisionTask, outcome, reason);
    String title =
        switch (outcome) {
          case "REJECTED" -> "流程事项已驳回";
          case "WITHDRAWN" -> "流程事项已撤回";
          default -> "流程事项已取消";
        };
    enqueueNotification(
        instance.getTenantId(),
        "instance:"
            + instance.getProcessInstanceId()
            + ":"
            + outcome
            + ":"
            + instance.getStarterId(),
        instance.getStarterId(),
        title,
        instance.getTitle() + " · " + reason,
        WorkflowLinks.matter(instance.getProcessInstanceId()));
    return instanceSummary(instance);
  }

  private WorkflowInstanceEntity requireLockedInstance(final String id) {
    WorkflowInstanceEntity instance = requireInstance(id);
    // Refresh after acquiring the lock: a concurrent transaction may have ended the process
    // since the first authorization-scoped read. Every platform mutation uses this boundary.
    entityManager.refresh(instance, LockModeType.PESSIMISTIC_WRITE);
    return instance;
  }

  private static void requireRunning(final WorkflowInstanceEntity instance) {
    if (!"RUNNING".equals(instance.getStatus())) {
      throw conflict("流程已经结束，请刷新后查看处理结果");
    }
  }

  private void recordAction(
      final WorkflowInstanceEntity instance,
      final Task task,
      final String type,
      final String comment) {
    WorkflowActionEntity action = new WorkflowActionEntity();
    action.setId(UUID.randomUUID().toString());
    action.setTenantId(instance.getTenantId());
    action.setProcessInstanceId(instance.getProcessInstanceId());
    action.setTaskId(task == null ? null : task.getId());
    action.setTaskDefinitionKey(task == null ? null : task.getTaskDefinitionKey());
    action.setDecisionTaskId(
        task != null && Set.of("APPROVED", "REJECTED").contains(type) ? task.getId() : null);
    action.setTerminalInstanceId(
        Set.of("REJECTED", "WITHDRAWN", "CANCELED").contains(type)
            ? instance.getProcessInstanceId()
            : null);
    action.setActionType(type);
    action.setActorId(userId());
    action.setComment(comment);
    action.setOccurredAt(Instant.now());
    entityManager.persist(action);
  }

  private PageResult<ProcessInstanceSummary> instancePage(
      final String keyword,
      final String requestedStatus,
      final String starter,
      final int page,
      final int size) {
    final String tenant = tenantId();
    PageBounds bounds = PageBounds.of(page, size);
    Search search = Search.of(keyword);
    String status = normalizeStatus(requestedStatus);
    String where =
        "i.tenantId = :tenant and (:starter is null or i.starterId = :starter)"
            + " and (:status is null or i.status = :status)"
            + " and (:search = '' or lower(i.title) like :pattern escape '!'"
            + " or lower(i.definitionName) like :pattern escape '!'"
            + " or lower(coalesce(i.businessKey, '')) like :pattern escape '!')";
    var query =
        entityManager.createQuery(
            "select i from WorkflowInstanceEntity i where "
                + where
                + " order by i.startedAt desc, i.processInstanceId desc",
            WorkflowInstanceEntity.class);
    bindInstanceQuery(query, tenant, starter, status, search);
    List<WorkflowInstanceEntity> rows =
        query.setFirstResult(bounds.offset()).setMaxResults(bounds.size()).getResultList();
    var countQuery =
        entityManager.createQuery(
            "select count(i) from WorkflowInstanceEntity i where " + where, Long.class);
    bindInstanceQuery(countQuery, tenant, starter, status, search);
    return new PageResult<>(
        rows.stream().map(this::instanceSummary).toList(),
        bounds.page(),
        bounds.size(),
        countQuery.getSingleResult());
  }

  private static void bindInstanceQuery(
      final jakarta.persistence.Query query,
      final String tenant,
      final String starter,
      final String status,
      final Search search) {
    query
        .setParameter("tenant", tenant)
        .setParameter("starter", starter)
        .setParameter("status", status)
        .setParameter("search", search.value())
        .setParameter("pattern", search.pattern());
  }

  private DefinitionDetail detail(final WorkflowDefinitionEntity definition) {
    List<VersionSummary> versions =
        entityManager
            .createQuery(
                "select new"
                    + " org.simplepoint.plugin.workflow.api.model.WorkflowModels$VersionSummary("
                    + "v.id,v.version,v.checksum,v.comment,v.flowableProcessDefinitionId,v.publishedBy,v.publishedAt)"
                    + " from WorkflowDefinitionVersionEntity v where v.tenantId = :tenant and"
                    + " v.definitionId = :definition order by v.version desc",
                VersionSummary.class)
            .setParameter("tenant", definition.getTenantId())
            .setParameter("definition", definition.getId())
            .getResultList();
    return new DefinitionDetail(
        definitionSummary(definition),
        definition.getDraftXml(),
        versions,
        definitionPermissions(),
        formContracts.read(definition.getDraftFormContract()));
  }

  private DefinitionSummary definitionSummary(final WorkflowDefinitionEntity definition) {
    String draftChecksum =
        definition.getDraftFormContract() == null
            ? checksum(definition.getDraftXml())
            : snapshotChecksum(definition);
    return new DefinitionSummary(
        definition.getId(),
        definition.getKey(),
        definition.getName(),
        definition.getCategory(),
        definition.getDescription(),
        definition.getStatus(),
        definition.getLatestVersion(),
        definition.getDraftRevision(),
        !Objects.equals(draftChecksum, definition.getPublishedChecksum()),
        definition.getCreatedBy(),
        definition.getCreatedAt(),
        definition.getUpdatedAt());
  }

  private VersionSummary versionSummary(final WorkflowDefinitionVersionEntity version) {
    return new VersionSummary(
        version.getId(),
        version.getVersion(),
        version.getChecksum(),
        version.getComment(),
        version.getFlowableProcessDefinitionId(),
        version.getPublishedBy(),
        version.getPublishedAt());
  }

  private ProcessInstanceSummary instanceSummary(final WorkflowInstanceEntity instance) {
    return new ProcessInstanceSummary(
        instance.getProcessInstanceId(),
        instance.getDefinitionId(),
        instance.getDefinitionKey(),
        instance.getDefinitionName(),
        instance.getDefinitionVersion(),
        instance.getBusinessKey(),
        instance.getTitle(),
        instance.getStarterId(),
        instance.getStatus(),
        instance.getStartedAt(),
        instance.getEndedAt());
  }

  private ProcessInstanceSummary effectiveInstanceSummary(final WorkflowInstanceEntity instance) {
    if (!"RUNNING".equals(instance.getStatus())) {
      return instanceSummary(instance);
    }
    var historic =
        historyService
            .createHistoricProcessInstanceQuery()
            .processInstanceId(instance.getProcessInstanceId())
            .processInstanceTenantId(instance.getTenantId())
            .singleResult();
    if (historic == null || historic.getEndTime() == null) {
      return instanceSummary(instance);
    }
    return new ProcessInstanceSummary(
        instance.getProcessInstanceId(),
        instance.getDefinitionId(),
        instance.getDefinitionKey(),
        instance.getDefinitionName(),
        instance.getDefinitionVersion(),
        instance.getBusinessKey(),
        instance.getTitle(),
        instance.getStarterId(),
        hasText(historic.getDeleteReason()) ? "CANCELED" : "COMPLETED",
        instance.getStartedAt(),
        instant(historic.getEndTime()));
  }

  private TaskSummary taskSummary(
      final Task task,
      final WorkflowInstanceEntity instance,
      final String user,
      final List<String> groups) {
    boolean assigned = user.equals(task.getAssignee());
    boolean claimable = !hasText(task.getAssignee()) && isCandidate(task.getId(), user, groups);
    return new TaskSummary(
        task.getId(),
        task.getProcessInstanceId(),
        value(instance, WorkflowInstanceEntity::getDefinitionKey),
        value(instance, WorkflowInstanceEntity::getDefinitionName),
        task.getName(),
        task.getTaskDefinitionKey(),
        task.getAssignee(),
        task.getOwner(),
        instant(task.getCreateTime()),
        instant(task.getDueDate()),
        null,
        task.getPriority(),
        claimable,
        assigned || claimable,
        value(instance, WorkflowInstanceEntity::getBusinessKey),
        value(instance, WorkflowInstanceEntity::getTitle));
  }

  private static TaskSummary taskSummary(
      final HistoricTaskInstance task, final WorkflowInstanceEntity instance) {
    return new TaskSummary(
        task.getId(),
        task.getProcessInstanceId(),
        value(instance, WorkflowInstanceEntity::getDefinitionKey),
        value(instance, WorkflowInstanceEntity::getDefinitionName),
        task.getName(),
        task.getTaskDefinitionKey(),
        task.getAssignee(),
        task.getOwner(),
        instant(task.getCreateTime()),
        instant(task.getDueDate()),
        instant(task.getEndTime()),
        task.getPriority(),
        false,
        false,
        value(instance, WorkflowInstanceEntity::getBusinessKey),
        value(instance, WorkflowInstanceEntity::getTitle));
  }

  private boolean isCandidate(final String taskId, final String user, final List<String> groups) {
    if (taskService.createTaskQuery().taskId(taskId).taskCandidateUser(user).count() > 0L) {
      return true;
    }
    return !groups.isEmpty()
        && taskService.createTaskQuery().taskId(taskId).taskCandidateGroupIn(groups).count() > 0L;
  }

  private Task requireTask(final String taskId, final String tenant) {
    Task task =
        taskService
            .createTaskQuery()
            .taskId(required(taskId, "任务 ID 不能为空", 64))
            .taskTenantId(tenant)
            .singleResult();
    if (task == null) {
      throw notFound("任务不存在或已办理");
    }
    return task;
  }

  private WorkflowDefinitionEntity requireDefinition(
      final String definitionId, final boolean lock) {
    String id = required(definitionId, "流程定义 ID 不能为空", 36);
    WorkflowDefinitionEntity definition =
        lock
            ? entityManager.find(WorkflowDefinitionEntity.class, id, LockModeType.PESSIMISTIC_WRITE)
            : entityManager.find(WorkflowDefinitionEntity.class, id);
    if (definition == null || !tenantId().equals(definition.getTenantId())) {
      throw notFound("流程定义不存在");
    }
    return definition;
  }

  private WorkflowDefinitionVersionEntity requireVersion(
      final WorkflowDefinitionEntity definition, final int version) {
    if (version < 1) {
      throw invalid("流程版本必须大于 0");
    }
    List<WorkflowDefinitionVersionEntity> rows =
        entityManager
            .createQuery(
                "select v from WorkflowDefinitionVersionEntity v"
                    + " where v.tenantId = :tenant and v.definitionId = :definition"
                    + " and v.version = :version",
                WorkflowDefinitionVersionEntity.class)
            .setParameter("tenant", definition.getTenantId())
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
    WorkflowInstanceEntity instance =
        entityManager.find(
            WorkflowInstanceEntity.class, required(processInstanceId, "流程实例 ID 不能为空", 64));
    if (instance == null || !tenantId().equals(instance.getTenantId())) {
      throw notFound("流程实例不存在");
    }
    return instance;
  }

  /** Never reinterpret running instances or silently start an old, non-executing loop model. */
  private void requireExecutableVersion(WorkflowDefinitionVersionEntity version) {
    BpmnExecutionSurfacePolicy.validate(BpmnDefinitionValidator.parse(version.getBpmnXml()));
    String owner = BpmnExecutionCompiler.standardLoopOwner(version.getBpmnXml());
    if (owner != null
        && !repositoryService
            .getDeploymentResourceNames(version.getFlowableDeploymentId())
            .contains(BpmnExecutionCompiler.MANIFEST_RESOURCE)) {
      throw new WorkflowModelingException(
          "loopRepublish", "此历史版本尚未启用标准循环执行，请联系流程管理员检查配置并重新发布；原版本和运行中的事项不会被修改", owner);
    }
    String dataOwner =
        BpmnDiagramCompilation.owner(BpmnDefinitionValidator.parse(version.getBpmnXml()));
    if (dataOwner != null
        && !repositoryService
            .getDeploymentResourceNames(version.getFlowableDeploymentId())
            .contains(BpmnExecutionCompiler.DIAGRAM_RESOURCE)) {
      throw new WorkflowModelingException(
          "diagramRepublish", "此历史版本的数据图形尚未与执行变量隔离，请联系流程管理员检查并重新发布；原版本和运行中的事项不会被修改", dataOwner);
    }
  }

  private Map<String, WorkflowInstanceEntity> instancesByIds(final Collection<String> ids) {
    Set<String> normalized =
        ids == null
            ? Set.of()
            : ids.stream()
                .filter(WorkflowCenterServiceImpl::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    if (normalized.isEmpty()) {
      return Map.of();
    }
    return entityManager
        .createQuery(
            "select i from WorkflowInstanceEntity i"
                + " where i.tenantId = :tenant and i.processInstanceId in :ids",
            WorkflowInstanceEntity.class)
        .setParameter("tenant", tenantId())
        .setParameter("ids", normalized)
        .getResultList()
        .stream()
        .collect(
            Collectors.toMap(WorkflowInstanceEntity::getProcessInstanceId, Function.identity()));
  }

  private Map<String, Object> safeVariables(final Map<String, Object> requested) {
    Map<String, Object> variables = new LinkedHashMap<>();
    if (requested == null || requested.isEmpty()) {
      return variables;
    }
    if (requested.size() > 100) {
      throw invalid("流程变量不能超过 100 个");
    }
    requested.forEach(
        (key, value) -> {
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
      return checksum(
          objectMapper.writeValueAsString(
              java.util.Arrays.asList(
                  definition.getDraftXml(),
                  definition.getName(),
                  definition.getCategory(),
                  definition.getDescription(),
                  formContracts.read(definition.getDraftFormContract()))));
    } catch (IOException exception) {
      throw new WorkflowException(Code.VALIDATION, "流程快照无法序列化", exception);
    }
  }

  private void validateExternalForm(
      final WorkflowFormContract.Binding binding,
      final String stage,
      final String taskDefinitionKey,
      final Map<String, Object> variables,
      final WorkflowFormContext context) {
    if (binding == null) {
      return;
    }
    WorkflowFormProvider provider = formProviders.get(binding.provider());
    if (provider == null) {
      throw conflict("此流程绑定的表单提供方尚未接入，暂时不能提交");
    }
    provider.validateSubmission(
        binding,
        stage,
        taskDefinitionKey,
        java.util.Collections.unmodifiableMap(variables),
        context);
  }

  private org.simplepoint.plugin.workflow.api.model.WorkflowInvocationContext formAuthority() {
    var authority = org.simplepoint.core.AuthorizationContextHolder.getContext();
    return new org.simplepoint.plugin.workflow.api.model.WorkflowInvocationContext(
        tenantId(),
        userId(),
        Boolean.TRUE.equals(authority.getIsAdministrator()),
        authority.getResources() == null ? Set.of() : Set.copyOf(authority.getResources()),
        Set.copyOf(roles()));
  }

  private RuntimeForm runtimeForm(
      int version,
      WorkflowFormContract contract,
      WorkflowFormContract.Binding binding,
      Map<String, Object> source,
      String stage,
      String definition,
      String instance,
      String businessKey) {
    var projected = formContracts.project(contract, source, stage);
    Set<String> protectedVariables = Set.of();
    if (contract.startBinding() != null) {
      var provider = formProviders.get(contract.startBinding().provider());
      if (provider == null || provider.protectsMappedVariables()) {
        if (binding == null && "task".equals(stage)) {
          binding = contract.startBinding();
        }
        protectedVariables = Set.copyOf(contract.startBinding().variableMappings().values());
        protectedVariables.forEach(projected::remove);
      }
    }
    return new RuntimeForm(
        version,
        contract,
        binding,
        projected,
        instance,
        businessKey,
        definition,
        protectedVariables);
  }

  private void validatePublishedBindings(WorkflowFormContract contract) {
    if (contract.startBinding() != null) {
      validatePublishedBinding(contract.startBinding(), "start", null, contract);
    }
    contract
        .taskBindings()
        .forEach((task, binding) -> validatePublishedBinding(binding, "task", task, contract));
  }

  private void validatePublishedBinding(
      WorkflowFormContract.Binding binding,
      String stage,
      String task,
      WorkflowFormContract contract) {
    var provider = formProviders.get(binding.provider());
    if (provider == null) {
      throw conflict("此流程绑定的表单提供方尚未接入，暂时不能发布");
    }
    provider.validateBinding(binding, stage, task, contract, tenantId());
  }

  private String businessLink(WorkflowFormContract.Binding binding, String businessKey) {
    if (binding == null || !formProviders.get(binding.provider()).requiresBusinessReference()) {
      return null;
    }
    if (businessKey == null) {
      throw invalid("绑定业务表单的流程必须引用已接受的表单提交");
    }
    try {
      return checksum(
          objectMapper.writeValueAsString(List.of(tenantId(), binding.provider(), businessKey)));
    } catch (IOException failure) {
      throw new WorkflowException(Code.VALIDATION, "业务提交标识无效", failure);
    }
  }

  private void validateDirectoryVariables(
      final WorkflowFormContract contract, final Map<String, Object> values, final String tenant) {
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
      if (!new LinkedHashSet<>(tenantService.resolveActiveMemberIds(tenant, batch))
          .containsAll(batch)) {
        throw invalid("流程变量中的用户必须是当前组织的有效成员");
      }
    }
    validateRoleAuthorities(tenant, roles);
  }

  private void validateRoleAuthorities(final String tenant, final Collection<String> roles) {
    List<String> requested = List.copyOf(roles);
    for (int offset = 0; offset < requested.size(); offset += 50) {
      List<String> batch = requested.subList(offset, Math.min(offset + 50, requested.size()));
      if (!new LinkedHashSet<>(tenantService.resolveActiveRoleAuthorities(tenant, batch))
          .containsAll(batch)) {
        throw invalid("任务候选角色和流程变量中的角色必须属于当前组织且未被删除");
      }
    }
  }

  private Object safeValue(final Object value, final int depth) {
    if (value instanceof Number number && !Double.isFinite(number.doubleValue())) {
      throw invalid("流程变量数值必须是有限数值");
    }
    if (value == null
        || value instanceof String
        || value instanceof Number
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
      map.forEach(
          (key, item) -> {
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
      final WorkflowDefinitionEntity definition, final long expectedRevision) {
    if (definition.getDraftRevision() != expectedRevision) {
      throw conflict("流程草稿已被其他操作更新，请刷新后重试");
    }
  }

  private static String checksum(final String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
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

  private static String required(final String value, final String message, final int maxLength) {
    if (!hasText(value)) {
      throw invalid(message);
    }
    String normalized = value.trim();
    if (normalized.length() > maxLength) {
      throw invalid(message.replace("不能为空", "不能超过 " + maxLength + " 个字符"));
    }
    return normalized;
  }

  private static String optional(final String value, final int maxLength, final String label) {
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
    return WorkflowAccess.tenantId();
  }

  private static String userId() {
    return WorkflowAccess.userId();
  }

  private static List<String> roles() {
    return WorkflowAccess.roles();
  }

  private static boolean allowed(final String authority) {
    return WorkflowAccess.allowed(authority);
  }

  private static void requireAuthority(final String authority) {
    WorkflowAccess.requireAuthority(authority);
  }

  private Set<String> copiedUsers(
      final StartProcessCommand command, final String tenant, final String starter) {
    if (command == null || command.ccUserIds() == null || command.ccUserIds().isEmpty()) {
      return Set.of();
    }
    Set<String> requested =
        command.ccUserIds().stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(WorkflowCenterServiceImpl::hasText)
            .filter(user -> !starter.equals(user))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    if (requested.size() > 50) {
      throw invalid("每个流程实例最多抄送 50 人");
    }
    Set<String> members =
        new LinkedHashSet<>(tenantService.resolveActiveMemberIds(tenant, requested));
    if (!members.containsAll(requested)) {
      throw invalid("抄送人必须是当前组织的有效成员");
    }
    return requested;
  }

  private void persistWatchers(final WorkflowInstanceEntity instance, final Set<String> watchers) {
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
      final WorkflowInstanceEntity instance, final Set<String> watchers) {
    watchers.forEach(
        user ->
            enqueueNotification(
                instance.getTenantId(),
                "instance:" + instance.getProcessInstanceId() + ":copied:" + user,
                user,
                "流程事项已抄送给你",
                instance.getTitle() + " · " + instance.getDefinitionName(),
                WorkflowLinks.copied(instance.getProcessInstanceId())));
  }

  private void enqueueTaskNotifications(final WorkflowInstanceEntity instance) {
    List<Task> tasks =
        taskService
            .createTaskQuery()
            .processInstanceId(instance.getProcessInstanceId())
            .taskTenantId(instance.getTenantId())
            .active()
            .list();
    Map<String, Set<String>> taskRecipients = new LinkedHashMap<>();
    Map<String, Set<String>> taskRoles = new LinkedHashMap<>();
    Set<String> allRecipients = new LinkedHashSet<>();
    Set<String> candidateRoles = new LinkedHashSet<>();
    for (Task task : tasks) {
      Set<String> recipients = new LinkedHashSet<>();
      Set<String> roles = new LinkedHashSet<>();
      if (hasText(task.getAssignee())) {
        recipients.add(task.getAssignee());
      }
      taskService.getIdentityLinksForTask(task.getId()).stream()
          .filter(link -> "candidate".equals(link.getType()))
          .forEach(
              link -> {
                if (hasText(link.getUserId())) {
                  recipients.add(link.getUserId());
                }
                if (hasText(link.getGroupId())) {
                  roles.add(link.getGroupId());
                }
              });
      taskRecipients.put(task.getId(), recipients);
      taskRoles.put(task.getId(), roles);
      candidateRoles.addAll(roles);
      allRecipients.addAll(recipients);
    }
    // This also covers legacy/advanced expressions: evaluate in Flowable, then validate before
    // committing the engine transaction or creating any notification for an out-of-tenant user.
    validateTaskDirectory(instance.getTenantId(), allRecipients, candidateRoles);
    for (Task task : tasks) {
      if (hasText(task.getAssignee())) {
        // Candidate identity links may remain after assignment; only the actual assignee can act.
        enqueueTaskNotifications(instance, task, List.of(task.getAssignee()));
        continue;
      }
      enqueueTaskNotifications(instance, task, taskRecipients.get(task.getId()));
      List<String> roles = List.copyOf(taskRoles.get(task.getId()));
      for (int offset = 0; offset < roles.size(); offset += 50) {
        List<String> roleBatch = roles.subList(offset, Math.min(offset + 50, roles.size()));
        String cursor = null;
        while (true) {
          List<String> recipients =
              List.copyOf(
                  tenantService.resolveActiveRoleMemberIds(
                      instance.getTenantId(), roleBatch, cursor, 200));
          if (recipients.size() > 200) {
            throw new IllegalStateException("角色接收人查询未遵守分页契约");
          }
          enqueueTaskNotifications(instance, task, recipients);
          if (recipients.size() < 200) {
            break;
          }
          String next = recipients.getLast();
          if (next.equals(cursor)) {
            throw new IllegalStateException("角色接收人分页游标未推进");
          }
          cursor = next;
        }
      }
    }
  }

  private void enqueueTaskNotifications(
      WorkflowInstanceEntity instance, Task task, Collection<String> recipients) {
    List<String> users = recipients.stream().distinct().toList();
    String prefix = "task:" + task.getId() + ":created:";
    for (int offset = 0; offset < users.size(); offset += 200) {
      List<String> batch = users.subList(offset, Math.min(offset + 200, users.size()));
      // One idempotency lookup per bounded batch, not one SELECT + flush per recipient.
      Set<String> existing =
          new LinkedHashSet<>(
              entityManager
                  .createQuery(
                      "select o.eventKey from WorkflowNotificationOutboxEntity o where o.eventKey"
                          + " in :keys",
                      String.class)
                  .setParameter("keys", batch.stream().map(user -> prefix + user).toList())
                  .getResultList());
      batch.stream()
          .filter(user -> !existing.contains(prefix + user))
          .forEach(
              user ->
                  persistNotification(
                      instance.getTenantId(),
                      prefix + user,
                      user,
                      "你有新的流程待办",
                      instance.getTitle() + " · " + task.getName(),
                      WorkflowLinks.task(instance.getProcessInstanceId(), task.getId())));
    }
  }

  /** Native-job pre-commit boundary; does not acquire any platform lock or create a message. */
  public void validateEngineAssignments(final String processInstanceId) {
    Map<String, Set<String>> usersByTenant = new LinkedHashMap<>();
    Map<String, Set<String>> rolesByTenant = new LinkedHashMap<>();
    for (Task task :
        taskService.createTaskQuery().processInstanceId(processInstanceId).active().list()) {
      String tenant = task.getTenantId();
      if (!hasText(tenant)) {
        throw invalid("工作流任务必须属于有效组织");
      }
      Set<String> users = usersByTenant.computeIfAbsent(tenant, ignored -> new LinkedHashSet<>());
      Set<String> roles = rolesByTenant.computeIfAbsent(tenant, ignored -> new LinkedHashSet<>());
      if (hasText(task.getAssignee())) {
        users.add(task.getAssignee());
      }
      taskService.getIdentityLinksForTask(task.getId()).stream()
          .filter(link -> "candidate".equals(link.getType()))
          .forEach(
              link -> {
                if (hasText(link.getUserId())) {
                  users.add(link.getUserId());
                }
                if (hasText(link.getGroupId())) {
                  roles.add(link.getGroupId());
                }
              });
    }
    usersByTenant.forEach(
        (tenant, users) -> validateTaskDirectory(tenant, users, rolesByTenant.get(tenant)));
  }

  private void validateTaskDirectory(
      String tenant, Collection<String> users, Collection<String> roles) {
    List<String> requested = List.copyOf(users);
    for (int offset = 0; offset < requested.size(); offset += 50) {
      List<String> batch = requested.subList(offset, Math.min(offset + 50, requested.size()));
      if (!new LinkedHashSet<>(tenantService.resolveActiveMemberIds(tenant, batch))
          .containsAll(batch)) {
        throw invalid("任务办理人和候选用户必须是当前组织的有效成员");
      }
    }
    validateRoleAuthorities(tenant, roles);
  }

  /**
   * Internal queue consumer; derives tenant ownership from persisted data, never a request context.
   */
  @Transactional
  public void synchronizeEngineState(final String processInstanceId) {
    WorkflowInstanceEntity instance =
        entityManager.find(
            WorkflowInstanceEntity.class, processInstanceId, LockModeType.PESSIMISTIC_WRITE);
    if (instance == null || !"RUNNING".equals(instance.getStatus())) {
      return;
    }
    var history =
        historyService
            .createHistoricProcessInstanceQuery()
            .processInstanceId(processInstanceId)
            .processInstanceTenantId(instance.getTenantId())
            .singleResult();
    if (history == null) {
      throw new IllegalStateException("Workflow instance has no engine history");
    }
    if (history.getEndTime() != null) {
      instance.setStatus(history.getDeleteReason() == null ? "COMPLETED" : "CANCELED");
      instance.setEndedAt(history.getEndTime().toInstant());
      if ("COMPLETED".equals(instance.getStatus())) {
        enqueueInstanceCompleted(instance);
      }
    } else {
      enqueueTaskNotifications(instance);
    }
  }

  private void enqueueInstanceCompleted(final WorkflowInstanceEntity instance) {
    enqueueNotification(
        instance.getTenantId(),
        "instance:" + instance.getProcessInstanceId() + ":completed:" + instance.getStarterId(),
        instance.getStarterId(),
        "流程事项已完成",
        instance.getTitle() + " · " + instance.getDefinitionName(),
        WorkflowLinks.matter(instance.getProcessInstanceId()));
  }

  private void enqueueNotification(
      final String tenant,
      final String eventKey,
      final String targetUser,
      final String title,
      final String content,
      final String link) {
    Long existing =
        entityManager
            .createQuery(
                "select count(o) from WorkflowNotificationOutboxEntity o where o.eventKey = :key",
                Long.class)
            .setParameter("key", eventKey)
            .getSingleResult();
    if (existing > 0L) {
      return;
    }
    persistNotification(tenant, eventKey, targetUser, title, content, link);
  }

  private void persistNotification(
      String tenant,
      String eventKey,
      String targetUser,
      String title,
      String content,
      String link) {
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
    if (allowed(WorkflowResourceCodes.INSTANCES_VIEW) || user.equals(instance.getStarterId())) {
      return;
    }
    boolean involved =
        historyService
                .createHistoricTaskInstanceQuery()
                .processInstanceId(instance.getProcessInstanceId())
                .taskInvolvedUser(user)
                .count()
            > 0;
    if (!involved) {
      involved =
          entityManager
                  .createQuery(
                      "select count(w) from WorkflowInstanceWatcherEntity w"
                          + " where w.tenantId = :tenant and w.processInstanceId = :instance"
                          + " and w.userId = :user",
                      Long.class)
                  .setParameter("tenant", instance.getTenantId())
                  .setParameter("instance", instance.getProcessInstanceId())
                  .setParameter("user", user)
                  .getSingleResult()
              > 0L;
    }
    if (!involved && "RUNNING".equals(instance.getStatus())) {
      involved =
          taskService
                  .createTaskQuery()
                  .processInstanceId(instance.getProcessInstanceId())
                  .taskCandidateOrAssigned(user)
                  .count()
              > 0;
      List<String> groups = roles();
      if (!involved && !groups.isEmpty()) {
        involved =
            taskService
                    .createTaskQuery()
                    .processInstanceId(instance.getProcessInstanceId())
                    .taskCandidateGroupIn(groups)
                    .count()
                > 0;
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
