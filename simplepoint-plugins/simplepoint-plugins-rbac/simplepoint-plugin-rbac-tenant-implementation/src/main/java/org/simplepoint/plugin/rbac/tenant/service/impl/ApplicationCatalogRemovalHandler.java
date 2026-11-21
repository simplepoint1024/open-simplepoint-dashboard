package org.simplepoint.plugin.rbac.tenant.service.impl;

import org.simplepoint.events.api.EventHandler;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventPublisher;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.platform.resource.ApplicationCatalogRemovalEvents;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Removes the offline catalog under the identity owner's tenant lock. */
@Component
@ConditionalOnProperty(name = {"simplepoint.events.enabled", "simplepoint.rbac.managed-resource.enabled"},
    havingValue = "true")
public class ApplicationCatalogRemovalHandler implements EventHandler {
  private final ManagedTenantResourceServiceImpl resources;
  private final EventPublisher events;

  public ApplicationCatalogRemovalHandler(ManagedTenantResourceServiceImpl resources, EventPublisher events) {
    this.resources = resources;
    this.events = events;
  }

  @Override
  public EventSubscription subscription() {
    return ApplicationCatalogRemovalEvents.AUTHORIZATION;
  }

  @Override
  public void handle(EventMessage event) {
    resources.removeApplicationCatalog(event);
    events.publish(ApplicationCatalogRemovalEvents.completed(event));
  }
}
