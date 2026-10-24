package org.simplepoint.plugin.workflow.service;

import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.flowable.engine.HistoryService;
import org.simplepoint.plugin.rbac.tenant.api.service.AuditIdentityDirectory;
import org.simplepoint.plugin.rbac.tenant.api.vo.AuditIdentityRequest;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.model.WorkflowAuditTrail;
import org.simplepoint.plugin.workflow.api.model.WorkflowMatter;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PageResult;
import org.simplepoint.plugin.workflow.api.model.WorkflowProgress.IdentityLabel;
import org.simplepoint.plugin.workflow.api.model.WorkflowProgress.NodeExecution;
import org.simplepoint.plugin.workflow.entity.WorkflowActionEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowCandidateReadModel;
import org.simplepoint.plugin.workflow.entity.WorkflowInboxReadModel;
import org.simplepoint.plugin.workflow.entity.WorkflowInstanceEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowMatterReadModel;

/** Called only after instance visibility authorization; bounded, batch-enriched node history. */
final class WorkflowRuntimeDetails {
  private final EntityManager em;
  private final HistoryService history;
  private final AuditIdentityDirectory identities;

  WorkflowRuntimeDetails(
      EntityManager em, HistoryService history, AuditIdentityDirectory identities) {
    this.em = em;
    this.history = history;
    this.identities = identities;
  }

  WorkflowMatter matter(String id) {
    var row = em.find(WorkflowMatterReadModel.class, id);
    if (row == null || !WorkflowAccess.tenantId().equals(row.getTenantId())) {
      throw new WorkflowException(WorkflowException.Code.NOT_FOUND, "流程事项不存在");
    }
    return WorkflowMatterProjection.item(
        row, names(row.getTenantId(), List.of(row.getStarterId())).get(row.getStarterId()));
  }

  WorkflowAuditTrail audit(WorkflowInstanceEntity instance, int page, int size) {
    if (page < 0 || size < 1 || size > 50 || (long) page * size > Integer.MAX_VALUE) {
      throw new WorkflowException(WorkflowException.Code.VALIDATION, "操作记录查询范围无效");
    }
    String tenant = instance.getTenantId();
    String id = instance.getProcessInstanceId();
    String boundary = " where a.tenantId = :tenant and a.processInstanceId = :instance";
    long total =
        em.createQuery("select count(a) from WorkflowActionEntity a" + boundary, Long.class)
            .setParameter("tenant", tenant)
            .setParameter("instance", id)
            .getSingleResult();
    boolean complete =
        !em.createQuery(
                "select a.id from WorkflowActionEntity a"
                    + boundary
                    + " and a.actionType in ('STARTED', 'RESUBMITTED')",
                String.class)
            .setParameter("tenant", tenant)
            .setParameter("instance", id)
            .setMaxResults(1)
            .getResultList()
            .isEmpty();
    var actions =
        em.createQuery(
                "select a from WorkflowActionEntity a"
                    + boundary
                    + " order by a.occurredAt desc, a.id desc",
                WorkflowActionEntity.class)
            .setParameter("tenant", tenant)
            .setParameter("instance", id)
            .setFirstResult(page * size)
            .setMaxResults(size)
            .getResultList();
    var actors =
        names(tenant, actions.stream().map(WorkflowActionEntity::getActorId).distinct().toList());
    var taskIds =
        actions.stream()
            .map(WorkflowActionEntity::getTaskId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    Map<String, String> taskNames = new LinkedHashMap<>();
    if (!taskIds.isEmpty()) {
      em.createQuery(
              "select t from WorkflowInboxReadModel t where t.tenantId = :tenant"
                  + " and t.processInstanceId = :instance and t.taskId in :tasks",
              WorkflowInboxReadModel.class)
          .setParameter("tenant", tenant)
          .setParameter("instance", id)
          .setParameter("tasks", taskIds)
          .setMaxResults(50)
          .getResultList()
          .forEach(task -> taskNames.put(task.getTaskId(), task.getTaskName()));
    }
    var events =
        actions.stream()
            .map(
                action ->
                    new WorkflowAuditTrail.Event(
                        action.getId(),
                        action.getActionType(),
                        actors.get(action.getActorId()),
                        action.getComment(),
                        action.getTaskDefinitionKey(),
                        taskNames.get(action.getTaskId()),
                        action.getOccurredAt()))
            .toList();
    return new WorkflowAuditTrail(new PageResult<>(events, page, size, total), complete);
  }

  PageResult<NodeExecution> node(
      WorkflowInstanceEntity instance, String nodeId, int page, int size, boolean activeOnly) {
    if (nodeId == null
        || nodeId.isBlank()
        || nodeId.length() > 255
        || page < 0
        || size < 1
        || size > 50
        || (long) page * size > Integer.MAX_VALUE) {
      throw new WorkflowException(WorkflowException.Code.VALIDATION, "节点记录查询范围无效");
    }
    var query =
        history
            .createHistoricActivityInstanceQuery()
            .processInstanceId(instance.getProcessInstanceId())
            .tenantIdIn(List.of(instance.getTenantId()))
            .activityId(nodeId)
            .orderByHistoricActivityInstanceStartTime()
            .desc()
            .orderByHistoricActivityInstanceId()
            .desc();
    if (activeOnly) {
      query.unfinished();
    }
    long total = query.count();
    var occurrences = query.listPage(page * size, size);
    var taskIds =
        occurrences.stream()
            .map(value -> value.getTaskId())
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    if (taskIds.isEmpty()) {
      return new PageResult<>(
          occurrences.stream()
              .map(
                  activity ->
                      new NodeExecution(
                          activity.getId(),
                          activity.getActivityId(),
                          activity.getActivityName(),
                          activity.getActivityType(),
                          null,
                          state(
                              activity.getEndTime(),
                              activity.getDeleteReason(),
                              instance.getStatus()),
                          false,
                          null,
                          null,
                          null,
                          null,
                          instant(activity.getStartTime()),
                          instant(activity.getEndTime()),
                          List.of(),
                          List.of()))
              .toList(),
          page,
          size,
          total);
    }

    String tenant = instance.getTenantId();
    Map<String, WorkflowInboxReadModel> tasks =
        em
            .createQuery(
                "select t from WorkflowInboxReadModel t where t.tenantId = :tenant and"
                    + " t.processInstanceId = :instance and t.taskId in :tasks",
                WorkflowInboxReadModel.class)
            .setParameter("tenant", tenant)
            .setParameter("instance", instance.getProcessInstanceId())
            .setParameter("tasks", taskIds)
            .setMaxResults(50)
            .getResultList()
            .stream()
            .collect(Collectors.toMap(WorkflowInboxReadModel::getTaskId, Function.identity()));
    // Query only task IDs confirmed by the tenant-owned instance projection above.
    List<WorkflowCandidateReadModel> candidates =
        tasks.isEmpty()
            ? List.of()
            : em.createQuery(
                    "select c from WorkflowCandidateReadModel c where c.taskId in :tasks order by"
                        + " c.id",
                    WorkflowCandidateReadModel.class)
                .setParameter("tasks", tasks.keySet())
                .setMaxResults(5001)
                .getResultList();
    if (candidates.size() > 5000) {
      throw new WorkflowException(WorkflowException.Code.VALIDATION, "节点候选范围超过展示上限");
    }
    var users =
        names(
            tenant,
            java.util.stream.Stream.concat(
                    candidates.stream().map(WorkflowCandidateReadModel::getUserId),
                    tasks.values().stream()
                        .flatMap(
                            task ->
                                java.util.stream.Stream.of(
                                    task.getAssigneeId(), task.getActorId())))
                .filter(Objects::nonNull)
                .distinct()
                .toList());
    var roles =
        roleNames(
            tenant,
            candidates.stream()
                .map(WorkflowCandidateReadModel::getGroupId)
                .filter(Objects::nonNull)
                .distinct()
                .toList());
    var links =
        candidates.stream().collect(Collectors.groupingBy(WorkflowCandidateReadModel::getTaskId));
    return new PageResult<>(
        occurrences.stream()
            .map(
                activity -> {
                  var task = tasks.get(activity.getTaskId());
                  var candidateLinks = links.getOrDefault(activity.getTaskId(), List.of());
                  return new NodeExecution(
                      activity.getId(),
                      activity.getActivityId(),
                      activity.getActivityName(),
                      activity.getActivityType(),
                      activity.getTaskId(),
                      state(
                          activity.getEndTime(), activity.getDeleteReason(), instance.getStatus()),
                      task != null && task.getAssigneeId() != null,
                      task == null ? null : users.get(task.getAssigneeId()),
                      task == null ? null : users.get(task.getActorId()),
                      task == null ? null : task.getOutcome(),
                      task == null ? null : task.getComment(),
                      instant(activity.getStartTime()),
                      instant(activity.getEndTime()),
                      candidateLinks.stream()
                          .map(WorkflowCandidateReadModel::getUserId)
                          .filter(Objects::nonNull)
                          .distinct()
                          .map(id -> new IdentityLabel(users.get(id)))
                          .toList(),
                      candidateLinks.stream()
                          .map(WorkflowCandidateReadModel::getGroupId)
                          .filter(Objects::nonNull)
                          .distinct()
                          .map(id -> new IdentityLabel(roles.get(id)))
                          .toList());
                })
            .toList(),
        page,
        size,
        total);
  }

  private Map<String, String> names(String tenant, List<String> ids) {
    return labels(tenant, ids, false);
  }

  private Map<String, String> roleNames(String tenant, List<String> ids) {
    return labels(tenant, ids, true);
  }

  private Map<String, String> labels(String tenant, List<String> ids, boolean roles) {
    Map<String, String> result = new LinkedHashMap<>();
    for (var batch : batches(ids.stream().filter(Objects::nonNull).distinct().toList())) {
      var requested = Set.copyOf(batch);
      var response =
          identities.resolve(
              new AuditIdentityRequest(
                  tenant,
                  false,
                  roles ? Set.of() : requested,
                  Set.of(),
                  roles ? requested : Set.of()));
      if (response == null || !tenant.equals(response.tenantId())) {
        throw new WorkflowException(WorkflowException.Code.ENGINE_FAILURE, "身份名称目录返回的范围无效");
      }
      var values = roles ? response.roles() : response.users();
      for (String id : batch) {
        String name = values.get(id);
        if (name != null && !name.isBlank()) {
          result.put(id, name);
        }
      }
    }
    return result;
  }

  private static List<List<String>> batches(List<String> ids) {
    List<List<String>> batches = new ArrayList<>();
    for (int index = 0; index < ids.size(); index += 50) {
      batches.add(ids.subList(index, Math.min(ids.size(), index + 50)));
    }
    return batches;
  }

  private static String state(java.util.Date end, String reason, String outcome) {
    if (reason != null && !reason.isBlank()) {
      return reason.equals("simplepoint:" + outcome)
              && Set.of("REJECTED", "WITHDRAWN").contains(outcome)
          ? outcome
          : "CANCELED";
    }
    return end == null ? "ACTIVE" : "COMPLETED";
  }

  private static java.time.Instant instant(java.util.Date value) {
    return value == null ? null : value.toInstant();
  }
}
