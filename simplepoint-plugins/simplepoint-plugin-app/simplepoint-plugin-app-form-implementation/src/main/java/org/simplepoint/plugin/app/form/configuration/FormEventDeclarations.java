package org.simplepoint.plugin.app.form.configuration;

import java.util.List;
import org.simplepoint.events.api.EventDeclarations;
import org.simplepoint.events.api.EventPublication;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.platform.resource.ResourceLifecycleEvents;
import org.simplepoint.platform.resource.ApplicationCatalogRemovalEvents;
import org.simplepoint.plugin.workflow.api.event.WorkflowHistoryRemovalEvents;
import org.simplepoint.plugin.dna.runtime.api.event.FormStorageRemovalEvents;

/** Stable domain event contracts, independent of deployment host names. */
public final class FormEventDeclarations implements EventDeclarations {
  @Override
  public String identity() {
    return "app";
  }

  @Override
  public List<EventPublication> publications() {
    return List.of(ResourceLifecycleEvents.REQUEST_PUBLICATION, WorkflowHistoryRemovalEvents.ACK,
        FormStorageRemovalEvents.REQUEST, ApplicationCatalogRemovalEvents.REQUEST);
  }

  @Override
  public List<EventSubscription> subscriptions() {
    return java.util.stream.Stream.concat(ResourceLifecycleEvents.REQUIRED_PARTICIPANTS.stream()
        .sorted()
        .map(ResourceLifecycleEvents::completionSubscription),
        java.util.stream.Stream.of(WorkflowHistoryRemovalEvents.requestSubscription("app"),
            FormStorageRemovalEvents.APPLICATION, ApplicationCatalogRemovalEvents.APPLICATION))
        .toList();
  }
}
