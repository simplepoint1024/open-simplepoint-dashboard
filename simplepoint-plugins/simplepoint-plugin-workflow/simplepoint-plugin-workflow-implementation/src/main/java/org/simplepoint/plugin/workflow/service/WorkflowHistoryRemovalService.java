package org.simplepoint.plugin.workflow.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.flowable.engine.HistoryService;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventPublisher;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.WorkflowException.Code;
import org.simplepoint.plugin.workflow.api.constants.WorkflowResourceCodes;
import org.simplepoint.plugin.workflow.api.event.WorkflowHistoryRemovalEvents;
import org.simplepoint.plugin.workflow.api.model.WorkflowHistoryRemoval.Progress;
import org.simplepoint.plugin.workflow.api.model.WorkflowHistoryRemoval.Request;
import org.simplepoint.plugin.workflow.api.model.WorkflowLinks;
import org.simplepoint.plugin.workflow.entity.WorkflowBusinessLinkEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowHistoryRemovalBatchEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowHistoryRemovalEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowInstanceEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates terminal instance deletion with the owners of delivered messages and form history. */
@Service
@ConditionalOnProperty(prefix = "simplepoint.events", name = "enabled", havingValue = "true")
public class WorkflowHistoryRemovalService {
  public static final int MAX_BATCH = 100;
  private static final Set<String> TERMINAL = Set.of("COMPLETED", "REJECTED", "WITHDRAWN", "CANCELED");
  private final EntityManager entities;
  private final HistoryService history;
  private final EventPublisher events;

  public WorkflowHistoryRemovalService(EntityManager entities, HistoryService history, EventPublisher events) {
    this.entities = entities;
    this.history = history;
    this.events = events;
  }

  @Transactional
  public Progress request(Request request) {
    requireCapability();
    String tenant = WorkflowAccess.tenantId();
    String actor = WorkflowAccess.userId();
    var selection = selection(request);
    String batchKey = UUID.nameUUIDFromBytes((tenant + "\u0000" + request.id())
        .getBytes(StandardCharsets.UTF_8)).toString();
    entities.createQuery("insert into WorkflowHistoryRemovalBatchEntity (batchKey) values (:key) on conflict do nothing")
        .setParameter("key", batchKey).executeUpdate();
    entities.find(WorkflowHistoryRemovalBatchEntity.class, batchKey, LockModeType.PESSIMISTIC_WRITE);
    var prior = operations(tenant, request.id());
    if (!prior.isEmpty()) return replay(request.id(), actor, selection, prior);

    // Lock every member in stable order before checking dependencies or emitting any event.
    var instances = entities.createQuery("from WorkflowInstanceEntity i where i.tenantId = :tenant"
            + " and i.processInstanceId in :ids order by i.processInstanceId", WorkflowInstanceEntity.class)
        .setParameter("tenant", tenant).setParameter("ids", selection.keySet())
        .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
    if (instances.size() != selection.size()) throw new WorkflowException(Code.NOT_FOUND, "Selected workflow unavailable");

    var occupied = entities.createQuery("select r.instanceId from WorkflowHistoryRemovalEntity r"
            + " where r.instanceId in :ids", String.class)
        .setParameter("ids", selection.keySet()).setMaxResults(1).getResultList();
    if (!occupied.isEmpty()) throw blocked("Workflow history removal is already in progress");

    var successors = entities.createQuery("select i.processInstanceId from WorkflowInstanceEntity i"
            + " where i.tenantId = :tenant and i.previousInstanceId in :ids", String.class)
        .setParameter("tenant", tenant).setParameter("ids", selection.keySet()).setMaxResults(1).getResultList();
    if (!successors.isEmpty()) throw blocked("The process is referenced by a resubmission");

    var now = Instant.now();
    for (var instance : instances) {
      var id = instance.getProcessInstanceId();
      verifyTerminal(instance, selection.get(id));
      String submission = submission(instance);
      UUID operation = UUID.randomUUID();
      var row = new WorkflowHistoryRemovalEntity();
      row.setInstanceId(id);
      row.setTenantId(tenant);
      row.setActorId(actor);
      row.setOperationId(operation.toString());
      row.setBatchId(request.id().toString());
      row.setDefinitionVersion(instance.getDefinitionVersion());
      row.setEndedAt(selection.get(id));
      row.setRequestedAt(now);
      entities.persist(row);
      events.publish(WorkflowHistoryRemovalEvents.requested(tenant, id, instance.getDefinitionVersion(),
          operation, request.id(), submission));
    }
    return new Progress(request.id(), instances.size(), 0);
  }

  @Transactional(readOnly = true)
  public Progress progress(UUID id) {
    requireCapability();
    var operations = operations(WorkflowAccess.tenantId(), id);
    if (operations.isEmpty()) throw new WorkflowException(Code.NOT_FOUND, "Removal batch unavailable");
    return progress(id, operations);
  }

  public int maximumSelection() {
    return WorkflowAccess.canDelete(WorkflowResourceCodes.INSTANCES_VIEW, WorkflowResourceCodes.INSTANCES_DELETE)
        ? MAX_BATCH : 0;
  }

  /** Runs inside the durable inbox transaction and commits native history removal with its receipt. */
  @Transactional
  public void completed(EventMessage event) {
    WorkflowHistoryRemovalEvents.requireAcknowledgement(event);
    var row = entities.find(WorkflowHistoryRemovalEntity.class, event.resourceId(), LockModeType.PESSIMISTIC_WRITE);
    if (row == null || !row.getTenantId().equals(event.tenantId())
        || !row.getOperationId().equals(event.operationId()) || !row.getBatchId().equals(event.correlationId())
        || row.getDefinitionVersion() != event.generation()) {
      throw blocked("Acknowledgement does not match the requested removal");
    }
    if (row.getCompletedAt() != null) return;
    if ("app".equals(event.producer())) row.setAppCompleted(true);
    else row.setPersonalCompleted(true);
    if (row.isAppCompleted() && row.isPersonalCompleted()) {
      deleteHistory(row);
      row.setCompletedAt(Instant.now());
    }
  }

  private void deleteHistory(WorkflowHistoryRemovalEntity row) {
    String id = row.getInstanceId();
    // The engine signal dispatcher takes its queue row before the workflow instance.
    entities.createQuery("delete from WorkflowEngineSignalEntity s where s.processInstanceId = :id")
        .setParameter("id", id).executeUpdate();
    var instance = entities.find(WorkflowInstanceEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
    if (instance == null || !row.getTenantId().equals(instance.getTenantId())) {
      throw blocked("Workflow ownership changed during history removal");
    }
    verifyTerminal(instance, row.getEndedAt());
    String tenant = instance.getTenantId();
    entities.createQuery("delete from WorkflowNotificationOutboxEntity m where m.tenantId = :tenant"
            + " and (m.linkUrl in :matterLinks or m.linkUrl like :taskLink escape '!')")
        .setParameter("tenant", tenant)
        .setParameter("matterLinks", List.of(WorkflowLinks.matter(id), WorkflowLinks.copied(id)))
        .setParameter("taskLink", WorkflowLinks.taskPattern(id)).executeUpdate();
    for (String table : List.of("WorkflowInstanceWatcherEntity", "WorkflowActionEntity",
        "WorkflowPersonalArchiveEntity", "WorkflowBusinessLinkEntity")) {
      entities.createQuery("delete from " + table + " r where r.tenantId = :tenant and r.processInstanceId = :id")
          .setParameter("tenant", tenant).setParameter("id", id).executeUpdate();
    }
    entities.createQuery("update WorkflowOperationReceiptEntity r set r.purged = true, r.resultJson = '{}'"
            + " where r.tenantId = :tenant and r.processInstanceId = :id")
        .setParameter("tenant", tenant).setParameter("id", id).executeUpdate();
    history.deleteHistoricProcessInstance(id);
    entities.remove(instance);
  }

  private void verifyTerminal(WorkflowInstanceEntity instance, Instant observedEnd) {
    if (!TERMINAL.contains(instance.getStatus()) || !observedEnd.equals(instance.getEndedAt())) {
      throw blocked("Only a completed or terminated instance at the observed revision can be removed");
    }
    var nativeInstance = history.createHistoricProcessInstanceQuery().processInstanceId(instance.getProcessInstanceId())
        .processInstanceTenantId(instance.getTenantId()).finished().singleResult();
    if (nativeInstance == null || nativeInstance.getSuperProcessInstanceId() != null
        || history.createHistoricProcessInstanceQuery().superProcessInstanceId(instance.getProcessInstanceId())
            .count() != 0) {
      throw blocked("Workflow engine history is unavailable or belongs to a linked process");
    }
  }

  private String submission(WorkflowInstanceEntity instance) {
    var links = entities.createQuery("from WorkflowBusinessLinkEntity b where b.tenantId = :tenant"
            + " and b.processInstanceId = :id", WorkflowBusinessLinkEntity.class)
        .setParameter("tenant", instance.getTenantId()).setParameter("id", instance.getProcessInstanceId())
        .setMaxResults(2).getResultList();
    if (links.isEmpty()) return null;
    var link = links.getFirst();
    if (links.size() != 1 || !"simplepoint-app".equals(link.getProvider())
        || !link.getBusinessKey().matches("app-form:[a-f0-9]{64}")) {
      throw blocked("Workflow has an unsupported external business dependency");
    }
    return link.getBusinessKey().substring("app-form:".length());
  }

  private List<WorkflowHistoryRemovalEntity> operations(String tenant, UUID id) {
    return entities.createQuery("from WorkflowHistoryRemovalEntity r where r.tenantId = :tenant"
            + " and r.batchId = :batch order by r.instanceId", WorkflowHistoryRemovalEntity.class)
        .setParameter("tenant", tenant).setParameter("batch", id.toString())
        .setMaxResults(MAX_BATCH + 1).getResultList();
  }

  private static Progress replay(UUID id, String actor, TreeMap<String, Instant> selection,
      List<WorkflowHistoryRemovalEntity> previous) {
    if (previous.size() != selection.size() || previous.stream().anyMatch(row ->
        !row.getActorId().equals(actor) || !row.getEndedAt().equals(selection.get(row.getInstanceId())))) {
      throw blocked("A different request already uses this batch identity");
    }
    return progress(id, previous);
  }

  private static Progress progress(UUID id, List<WorkflowHistoryRemovalEntity> operations) {
    return new Progress(id, operations.size(), (int) operations.stream().filter(row -> row.getCompletedAt() != null).count());
  }

  private static TreeMap<String, Instant> selection(Request request) {
    if (request == null || request.id() == null || request.instances() == null
        || request.instances().isEmpty() || request.instances().size() > MAX_BATCH) {
      throw new WorkflowException(Code.VALIDATION, "Invalid history removal selection");
    }
    var selection = new TreeMap<String, Instant>();
    for (var row : request.instances()) {
      if (row == null || row.id() == null || row.id().isBlank() || row.id().length() > 64
          || row.endedAt() == null || selection.putIfAbsent(row.id(), row.endedAt()) != null) {
        throw new WorkflowException(Code.VALIDATION, "Each selected workflow requires a unique identity and end time");
      }
    }
    return selection;
  }

  private static void requireCapability() {
    WorkflowAccess.requireDeletion(WorkflowResourceCodes.INSTANCES_VIEW, WorkflowResourceCodes.INSTANCES_DELETE);
  }

  private static WorkflowException blocked(String message) {
    return new WorkflowException(Code.HISTORY_REMOVAL_BLOCKED, message);
  }
}
