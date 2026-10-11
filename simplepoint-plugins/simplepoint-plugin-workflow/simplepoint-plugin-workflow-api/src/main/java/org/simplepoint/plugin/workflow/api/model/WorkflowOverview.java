package org.simplepoint.plugin.workflow.api.model;

import java.util.List;

/**
 * Bounded personal-home summary; capabilities describe unavailable cards without disclosing rows.
 */
public record WorkflowOverview(
    boolean tenantWorkspace,
    boolean canViewTodos,
    boolean canViewMatters,
    long pendingTodos,
    long activeMatters,
    List<WorkflowInboxItem> todos,
    List<WorkflowMatter> matters) {}
