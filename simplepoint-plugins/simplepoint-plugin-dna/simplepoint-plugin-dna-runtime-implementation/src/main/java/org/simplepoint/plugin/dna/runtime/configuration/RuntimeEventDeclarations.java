package org.simplepoint.plugin.dna.runtime.configuration;

import java.util.List;
import org.simplepoint.events.api.EventDeclarations;
import org.simplepoint.events.api.EventPublication;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.platform.resource.ResourceLifecycleEvents;

/** Stable domain event contracts, independent of deployment host names. */
public final class RuntimeEventDeclarations implements EventDeclarations {
  @Override
  public String identity() {
    return "dna";
  }

  @Override
  public List<EventPublication> publications() {
    return List.of(ResourceLifecycleEvents.COMPLETION_PUBLICATION);
  }

  @Override
  public List<EventSubscription> subscriptions() {
    return List.of(ResourceLifecycleEvents.PROJECTION_SUBSCRIPTION);
  }
}
