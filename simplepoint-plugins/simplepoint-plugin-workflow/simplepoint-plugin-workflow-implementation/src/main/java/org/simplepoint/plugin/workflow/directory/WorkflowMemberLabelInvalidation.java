package org.simplepoint.plugin.workflow.directory;

import org.simplepoint.events.api.EventHandler;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.plugin.rbac.tenant.api.event.MemberLabelEvents;
import org.springframework.stereotype.Service;

/**
 * Commits only local refresh intent with inbox completion; it does not fetch or trust source data.
 */
@Service
public class WorkflowMemberLabelInvalidation implements EventHandler {
  private final WorkflowMemberLabelRefreshQueue queue;

  /** Requires the workflow-owned local queue. */
  public WorkflowMemberLabelInvalidation(WorkflowMemberLabelRefreshQueue queue) {
    this.queue = queue;
  }

  @Override
  public EventSubscription subscription() {
    return MemberLabelEvents.WORKFLOW_SUBSCRIPTION;
  }

  @Override
  public void handle(EventMessage event) {
    var revision = MemberLabelEvents.revision(event);
    queue.request(event.tenantId(), revision);
  }
}
