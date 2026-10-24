package org.simplepoint.plugin.app.form.configuration;

import java.util.List;
import org.simplepoint.events.api.EventDeclarations;
import org.simplepoint.events.api.EventPublication;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.platform.resource.ResourceLifecycleEvents;

/** Stable domain event contracts, independent of deployment host names. */
public final class FormEventDeclarations implements EventDeclarations {
  @Override
  public String identity() {
    return "app";
  }

  @Override
  public List<EventPublication> publications() {
    return List.of(ResourceLifecycleEvents.REQUEST_PUBLICATION);
  }

  @Override
  public List<EventSubscription> subscriptions() {
    return ResourceLifecycleEvents.REQUIRED_PARTICIPANTS.stream()
        .sorted()
        .map(ResourceLifecycleEvents::completionSubscription)
        .toList();
  }
}
