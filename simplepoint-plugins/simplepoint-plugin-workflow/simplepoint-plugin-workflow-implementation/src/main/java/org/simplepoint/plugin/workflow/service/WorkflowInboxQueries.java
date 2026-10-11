package org.simplepoint.plugin.workflow.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.query.InvalidQueryException;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.constants.WorkflowResourceCodes;
import org.simplepoint.plugin.workflow.api.model.WorkflowInboxItem;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PageResult;
import org.simplepoint.plugin.workflow.entity.WorkflowCandidateReadModel;
import org.simplepoint.plugin.workflow.entity.WorkflowInboxReadModel;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Personal inbox SQL queries. Authorization predicates are separate from all caller filters. */
@Service
@Transactional(readOnly = true)
public class WorkflowInboxQueries {
  private static final Set<String> TEXT_FIELDS =
      Set.of("title", "definitionName", "taskName", "starterName", "assigneeName", "actorName");
  private final EntityManager em;

  /** Creates the personal inbox adapter over native engine facts. */
  public WorkflowInboxQueries(EntityManager em) {
    this.em = em;
  }

  /** A notification/bookmark may target an item outside the currently visible table page. */
  public WorkflowInboxItem task(String id) {
    WorkflowAccess.requireAuthority(WorkflowResourceCodes.TASKS_VIEW);
    String tenant = WorkflowAccess.tenantId();
    String user = WorkflowAccess.userId();
    if (id == null || id.isBlank() || id.length() > 64) {
      throw new WorkflowException(WorkflowException.Code.NOT_FOUND, "待办不存在或当前用户无权查看");
    }
    var cb = em.getCriteriaBuilder();
    var query = cb.createQuery(WorkflowInboxReadModel.class);
    var root = query.from(WorkflowInboxReadModel.class);
    query
        .select(root)
        .where(
            cb.equal(root.get("taskId"), id),
            cb.or(
                boundary(cb, query, root, tenant, user, WorkflowAccess.roles(), true),
                boundary(cb, query, root, tenant, user, WorkflowAccess.roles(), false)));
    var rows = em.createQuery(query).setMaxResults(1).getResultList();
    if (rows.isEmpty()) {
      throw new WorkflowException(WorkflowException.Code.NOT_FOUND, "待办不存在或当前用户无权查看");
    }
    var row = rows.getFirst();
    return item(row, user, row.getHandledAt() == null && row.isActive());
  }

  /** Uses one SQL predicate contract for data and count; no current-page filtering/merging. */
  public PageResult<WorkflowInboxItem> query(
      String tab, Map<String, String> filters, Pageable requested) {
    WorkflowAccess.requireAuthority(WorkflowResourceCodes.TASKS_VIEW);
    String tenant = WorkflowAccess.tenantId();
    String user = WorkflowAccess.userId();
    List<String> roles = WorkflowAccess.roles();
    boolean pending =
        switch (tab) {
          case "pending" -> true;
          case "completed" -> false;
          default -> throw new InvalidQueryException("不支持的待办分组");
        };
    return WorkflowReadPages.query(
        em,
        WorkflowInboxReadModel.class,
        WorkflowInboxItem.class,
        filters,
        requested,
        pending ? "createdAt" : "handledAt",
        TEXT_FIELDS,
        (cb, query, root) -> boundary(cb, query, root, tenant, user, roles, pending),
        row -> item(row, user, pending));
  }

  private Predicate boundary(
      CriteriaBuilder cb,
      CommonAbstractCriteria query,
      Root<WorkflowInboxReadModel> root,
      String tenant,
      String user,
      List<String> roles,
      boolean pending) {
    Predicate scope;
    if (pending) {
      var candidate = query.subquery(Integer.class);
      var link = candidate.from(WorkflowCandidateReadModel.class);
      Predicate participant = cb.equal(link.get("userId"), user);
      if (!roles.isEmpty()) {
        participant = cb.or(participant, link.get("groupId").in(roles));
      }
      candidate
          .select(cb.literal(1))
          .where(cb.equal(link.get("taskId"), root.get("taskId")), participant);
      scope =
          cb.and(
              cb.isTrue(root.get("active")),
              cb.isNull(root.get("handledAt")),
              cb.or(
                  cb.equal(root.get("assigneeId"), user),
                  cb.and(cb.isNull(root.get("assigneeId")), cb.exists(candidate))));
    } else {
      scope =
          cb.and(
              cb.isNotNull(root.get("handledAt")),
              // Process-only actions carry their actor here. Candidates without an assignment or an
              // actual decision are never silently credited with another person's completed work.
              cb.or(
                  cb.equal(root.get("assigneeId"), user),
                  cb.equal(root.get("decisionActorId"), user)));
    }
    return cb.and(cb.equal(root.get("tenantId"), tenant), scope);
  }

  private WorkflowInboxItem item(WorkflowInboxReadModel row, String user, boolean pending) {
    boolean unassigned = row.getAssigneeId() == null;
    return new WorkflowInboxItem(
        row.getId(),
        row.getTaskId(),
        row.getProcessInstanceId(),
        row.getRowType(),
        row.getTitle(),
        row.getDefinitionName(),
        row.getTaskName(),
        row.getStarterName(),
        row.getAssigneeName(),
        row.getActorName(),
        row.getOutcome(),
        row.getInstanceStatus(),
        row.getCreatedAt(),
        row.getDueAt(),
        row.getHandledAt(),
        row.getPriority(),
        row.getComment(),
        !pending && user.equals(row.getActorId()),
        !unassigned,
        pending && unassigned && WorkflowAccess.allowed(WorkflowResourceCodes.TASKS_CLAIM),
        pending && WorkflowAccess.allowed(WorkflowResourceCodes.TASKS_COMPLETE));
  }

  /**
   * The same explicit fields drive schema controls and the SQL compiler. No internal IDs exposed.
   */
  public Map<String, Object> schema() {
    WorkflowAccess.requireAuthority(WorkflowResourceCodes.TASKS_VIEW);
    WorkflowAccess.tenantId();
    WorkflowAccess.userId();
    return WorkflowReadPages.schema(
        WorkflowInboxItem.class,
        "workflow.inbox",
        Map.of(
            "outcome",
                List.of("PENDING", "APPROVED", "COMPLETED", "REJECTED", "WITHDRAWN", "CANCELED"),
            "instanceStatus",
                List.of("RUNNING", "COMPLETED", "REJECTED", "WITHDRAWN", "CANCELED")));
  }
}
