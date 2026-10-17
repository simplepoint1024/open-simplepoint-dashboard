package org.simplepoint.plugin.rbac.tenant.service.impl;

import org.simplepoint.events.api.EventHandler;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventPublisher;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.platform.resource.ResourceLifecycleEvents;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Authorization module owns revocation and publishes completion in the same inbox transaction. */
@Component
@ConditionalOnProperty(prefix = "simplepoint.events", name = "enabled", havingValue = "true")
public class ManagedResourceOfflineHandler implements EventHandler {
  private final ManagedTenantResourceServiceImpl resources;
  private final EventPublisher events;

  /** Connects the resource owner to the platform event SPI. */
  public ManagedResourceOfflineHandler(ManagedTenantResourceServiceImpl resources, EventPublisher events) {
    this.resources = resources;
    this.events = events;
  }

  @Override
  public EventSubscription subscription() {
    return new EventSubscription("resource-offline.grants", "app", ResourceLifecycleEvents.OFFLINE_REQUESTED, 1);
  }

  @Override
  public void handle(EventMessage event) {
    subscription().validate(event);
    resources.offlineFromEvent(event);
    events.publish(ResourceLifecycleEvents.completed(event, "common"));
  }
}
