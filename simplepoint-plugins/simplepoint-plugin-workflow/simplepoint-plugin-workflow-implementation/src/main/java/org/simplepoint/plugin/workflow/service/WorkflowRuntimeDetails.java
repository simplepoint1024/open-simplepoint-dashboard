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
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.model.WorkflowAuditTrail;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PageResult;
import org.simplepoint.plugin.workflow.api.model.WorkflowProgress.IdentityLabel;
import org.simplepoint.plugin.workflow.api.model.WorkflowProgress.NodeExecution;
import org.simplepoint.plugin.workflow.entity.WorkflowActionEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowCandidateReadModel;
import org.simplepoint.plugin.workflow.entity.WorkflowInboxReadModel;
import org.simplepoint.plugin.workflow.entity.WorkflowInstanceEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowMemberReadModel;
import org.simplepoint.plugin.workflow.entity.WorkflowRoleReadModel;

/** Called only after instance visibility authorization; bounded, batch-enriched node history. */
final class WorkflowRuntimeDetails {
  private final EntityManager em;
  private final HistoryService history;

  WorkflowRuntimeDetails(EntityManager em, HistoryService history) {
    this.em = em;
    this.history = history;
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
            candidates.stream()
                .map(WorkflowCandidateReadModel::getUserId)
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
                      task == null ? null : task.getAssigneeName(),
                      task == null ? null : task.getActorName(),
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
    Map<String, String> result = new LinkedHashMap<>();
    for (var batch : batches(ids)) {
      em.createQuery(
              "select u from WorkflowMemberReadModel u where u.tenantId = :tenant and u.userId in"
                  + " :ids",
              WorkflowMemberReadModel.class)
          .setParameter("tenant", tenant)
          .setParameter("ids", batch)
          .setMaxResults(50)
          .getResultList()
          .forEach(user -> result.put(user.getUserId(), user.getName()));
    }
    return result;
  }

  private Map<String, String> roleNames(String tenant, List<String> ids) {
    Map<String, String> result = new LinkedHashMap<>();
    for (var batch : batches(ids)) {
      em
          .createQuery(
              "select r from WorkflowRoleReadModel r where r.tenantId = :tenant and r.authority in"
                  + " :ids order by r.id",
              WorkflowRoleReadModel.class)
          .setParameter("tenant", tenant)
          .setParameter("ids", batch)
          .setMaxResults(500)
          .getResultList()
          .stream()
          .filter(role -> role.getRoleName() != null && !role.getRoleName().isBlank())
          .forEach(
              role ->
                  result.merge(
                      role.getAuthority(),
                      role.getRoleName(),
                      (a, b) -> a.equals(b) ? a : a + " / " + b));
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
