package org.simplepoint.plugin.personal.module;

import java.util.List;
import org.simplepoint.events.api.EventDeclarations;
import org.simplepoint.events.api.EventPublication;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.plugin.workflow.api.event.WorkflowHistoryRemovalEvents;

/** The personal store owns workflow-driven removal of inbox copies. */
public final class PersonalEventDeclarations implements EventDeclarations {
  @Override
  public String identity() {
    return "personal";
  }

  @Override
  public List<EventPublication> publications() {
    return List.of(WorkflowHistoryRemovalEvents.ACK);
  }

  @Override
  public List<EventSubscription> subscriptions() {
    return List.of(WorkflowHistoryRemovalEvents.requestSubscription("personal"));
  }
}
