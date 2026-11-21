package org.simplepoint.plugin.workflow.config;

import java.util.List;
import org.simplepoint.events.api.EventDeclarations;
import org.simplepoint.events.api.EventPublication;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.plugin.rbac.tenant.api.event.MemberLabelEvents;
import org.simplepoint.plugin.workflow.api.event.WorkflowHistoryRemovalEvents;

/** Stable consumer identity for workflow-owned durable effects in every host topology. */
public final class WorkflowEventDeclarations implements EventDeclarations {
  @Override
  public String identity() {
    return "workflow";
  }

  @Override
  public List<EventPublication> publications() {
    return List.of(WorkflowHistoryRemovalEvents.REQUEST);
  }

  @Override
  public List<EventSubscription> subscriptions() {
    return List.of(MemberLabelEvents.WORKFLOW_SUBSCRIPTION,
        WorkflowHistoryRemovalEvents.acknowledgementSubscription("app"),
        WorkflowHistoryRemovalEvents.acknowledgementSubscription("personal"));
  }
}
