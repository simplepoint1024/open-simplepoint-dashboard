package org.simplepoint.plugin.workflow.service;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.query.InvalidQueryException;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.constants.WorkflowResourceCodes;
import org.simplepoint.plugin.workflow.api.model.WorkflowMatter;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PageResult;
import org.simplepoint.plugin.workflow.api.model.WorkflowStartOption;
import org.simplepoint.plugin.workflow.entity.WorkflowInstanceWatcherEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowMatterReadModel;
import org.simplepoint.plugin.workflow.entity.WorkflowStartableReadModel;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Starter/copied matters and the separately authorized manager view share one query contract. */
@Service
@Transactional(readOnly = true)
public class WorkflowMatterQueries {
  private final EntityManager em;

  /** Creates the tenant- and actor-scoped matter adapter. */
  public WorkflowMatterQueries(EntityManager em) {
    this.em = em;
  }

  /** Starting work does not require access to the workflow designer/drafts. */
  public PageResult<WorkflowStartOption> startable(Map<String, String> filters, Pageable page) {
    WorkflowAccess.requireAuthority(WorkflowResourceCodes.INSTANCES_START);
    final String tenant = WorkflowAccess.tenantId();
    WorkflowAccess.userId();
    return WorkflowReadPages.query(
        em,
        WorkflowStartableReadModel.class,
        WorkflowStartOption.class,
        filters,
        page,
        "name",
        Set.of("name", "category", "description"),
        (cb, query, root) -> cb.equal(root.get("tenantId"), tenant),
        WorkflowMatterQueries::startOption);
  }

  /** Selected-value hydration is bounded and shares the exact published/tenant boundary. */
  public List<WorkflowStartOption> startableOptions(List<String> ids) {
    WorkflowAccess.requireAuthority(WorkflowResourceCodes.INSTANCES_START);
    final String tenant = WorkflowAccess.tenantId();
    WorkflowAccess.userId();
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    if (ids.size() > 50
        || ids.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 36)) {
      throw new InvalidQueryException("流程选项查询范围超限");
    }
    return em
        .createQuery(
            "select d from WorkflowStartableReadModel d where d.tenantId = :tenant"
                + " and d.id in :ids order by d.name, d.id",
            WorkflowStartableReadModel.class)
        .setParameter("tenant", tenant)
        .setParameter("ids", ids)
        .setMaxResults(50)
        .getResultList()
        .stream()
        .map(WorkflowMatterQueries::startOption)
        .toList();
  }

  private static WorkflowStartOption startOption(WorkflowStartableReadModel definition) {
    return new WorkflowStartOption(
        definition.getId(),
        definition.getName(),
        definition.getCategory(),
        definition.getDescription(),
        definition.getLatestVersion());
  }

  private void requireView(String view) {
    switch (view) {
      case "started", "copied" -> {
        if (!WorkflowAccess.canViewPersonalMatters()) {
          WorkflowAccess.requireAuthority(WorkflowResourceCodes.INSTANCES_STARTED_VIEW);
        }
      }
      case "all" -> WorkflowAccess.requireAuthority(WorkflowResourceCodes.INSTANCES_VIEW);
      default -> throw new InvalidQueryException("不支持的事项分组");
    }
  }

  /** Queries started, copied or administrative matters with independent visibility predicates. */
  public PageResult<WorkflowMatter> query(String view, Map<String, String> filters, Pageable page) {
    requireView(view);
    String tenant = WorkflowAccess.tenantId();
    String user = WorkflowAccess.userId();
    return WorkflowReadPages.query(
        em,
        WorkflowMatterReadModel.class,
        WorkflowMatter.class,
        filters,
        page,
        "startedAt",
        Set.of("title", "definitionName", "businessKey", "starterName"),
        (cb, query, root) -> {
          var scope = cb.equal(root.get("tenantId"), tenant);
          if (view.equals("started")) {
            return cb.and(scope, cb.equal(root.get("starterId"), user));
          }
          if (view.equals("copied")) {
            var watcher = query.subquery(Integer.class);
            var link = watcher.from(WorkflowInstanceWatcherEntity.class);
            watcher
                .select(cb.literal(1))
                .where(
                    cb.equal(link.get("processInstanceId"), root.get("id")),
                    cb.equal(link.get("tenantId"), tenant),
                    cb.equal(link.get("userId"), user));
            return cb.and(scope, cb.exists(watcher));
          }
          return scope;
        },
        this::item);
  }

  /**
   * Already authorized instance details can reuse this projection, never a client-supplied tenant.
   */
  WorkflowMatter findVisibleProjection(String id) {
    var row = em.find(WorkflowMatterReadModel.class, id);
    if (row == null || !WorkflowAccess.tenantId().equals(row.getTenantId())) {
      throw new WorkflowException(WorkflowException.Code.NOT_FOUND, "流程事项不存在");
    }
    return item(row);
  }

  private WorkflowMatter item(WorkflowMatterReadModel row) {
    boolean running = "RUNNING".equals(row.getStatus());
    boolean own = WorkflowAccess.userId().equals(row.getStarterId());
    return new WorkflowMatter(
        row.getId(),
        row.getDefinitionId(),
        row.getTitle(),
        row.getDefinitionName(),
        row.getDefinitionVersion(),
        row.getBusinessKey(),
        row.getStarterName(),
        row.getStatus(),
        row.getStartedAt(),
        row.getEndedAt(),
        running && own && WorkflowAccess.canViewPersonalMatters(),
        running
            && WorkflowAccess.allowed(WorkflowResourceCodes.INSTANCES_CANCEL)
            && (own || WorkflowAccess.allowed(WorkflowResourceCodes.INSTANCES_VIEW)),
        own
            && row.isResubmissionVersionAvailable()
            && row.getNextInstanceId() == null
            && Set.of("REJECTED", "WITHDRAWN", "CANCELED").contains(row.getStatus())
            && WorkflowAccess.canViewPersonalMatters()
            && WorkflowAccess.allowed(WorkflowResourceCodes.INSTANCES_START),
        row.getPreviousInstanceId(),
        row.getNextInstanceId());
  }

  /** Returns the selected view's authorized query fields. */
  public Map<String, Object> schema(String view) {
    requireView(view);
    WorkflowAccess.tenantId();
    WorkflowAccess.userId();
    return WorkflowReadPages.schema(
        WorkflowMatter.class,
        "workflow.matter",
        Map.of("status", List.of("RUNNING", "COMPLETED", "REJECTED", "WITHDRAWN", "CANCELED")));
  }
}
