package org.simplepoint.plugin.workflow.service;

import java.util.List;
import java.util.Map;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.plugin.workflow.api.constants.WorkflowResourceCodes;
import org.simplepoint.plugin.workflow.api.model.WorkflowOverview;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reuses inbox/matter SQL boundaries so homepage counts cannot disagree with their lists. */
@Service
@Transactional(readOnly = true)
public class WorkflowOverviewQueries {
  private final WorkflowInboxQueries inbox;
  private final WorkflowMatterQueries matters;

  /** Shares personal query boundaries with dashboard summaries. */
  public WorkflowOverviewQueries(WorkflowInboxQueries inbox, WorkflowMatterQueries matters) {
    this.inbox = inbox;
    this.matters = matters;
  }

  /** Returns capability-gated summaries only in an organization workspace. */
  public WorkflowOverview overview() {
    WorkflowAccess.userId();
    if (AuthorizationContextHolder.getContext().getScopeType() != AuthorizationScopeType.TENANT) {
      return new WorkflowOverview(false, false, false, 0, 0, List.of(), List.of());
    }
    WorkflowAccess.tenantId();
    boolean canViewTodos = WorkflowAccess.allowed(WorkflowResourceCodes.TASKS_VIEW);
    boolean canViewMatters = WorkflowAccess.canViewPersonalMatters();
    var todos =
        canViewTodos
            ? inbox.query(
                "pending",
                Map.of(),
                PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "createdAt")))
            : null;
    var started =
        canViewMatters
            ? matters.query(
                "started",
                Map.of("status", "equals:RUNNING"),
                PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "startedAt")))
            : null;
    return new WorkflowOverview(
        true,
        canViewTodos,
        canViewMatters,
        todos == null ? 0 : todos.totalElements(),
        started == null ? 0 : started.totalElements(),
        todos == null ? List.of() : todos.content(),
        started == null ? List.of() : started.content());
  }
}
