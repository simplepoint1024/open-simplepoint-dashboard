package org.simplepoint.plugin.workflow.service;

import java.util.Set;
import org.simplepoint.plugin.workflow.api.constants.WorkflowResourceCodes;
import org.simplepoint.plugin.workflow.api.model.WorkflowMatter;
import org.simplepoint.plugin.workflow.entity.WorkflowMatterReadModel;

/** One capability mapping for fenced lists and separately authorized, bounded details. */
final class WorkflowMatterProjection {
  private WorkflowMatterProjection() {}

  static WorkflowMatter item(WorkflowMatterReadModel row, String starterName) {
    boolean running = "RUNNING".equals(row.getStatus());
    boolean own = WorkflowAccess.userId().equals(row.getStarterId());
    return new WorkflowMatter(
        row.getId(),
        row.getDefinitionId(),
        row.getTitle(),
        row.getDefinitionName(),
        row.getDefinitionVersion(),
        row.getBusinessKey(),
        starterName,
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
}
