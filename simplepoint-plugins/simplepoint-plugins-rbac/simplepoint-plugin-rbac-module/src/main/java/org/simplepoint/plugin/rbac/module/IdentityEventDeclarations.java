package org.simplepoint.plugin.rbac.module;

import java.util.List;
import org.simplepoint.events.api.EventDeclarations;
import org.simplepoint.events.api.EventPublication;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.platform.resource.ResourceLifecycleEvents;
import org.simplepoint.platform.resource.ApplicationCatalogRemovalEvents;
import org.simplepoint.plugin.auditing.logging.api.event.CredentialAuditContract;
import org.simplepoint.plugin.rbac.tenant.api.event.MemberLabelEvents;

/** Stable domain event contracts, independent of deployment host names. */
public final class IdentityEventDeclarations implements EventDeclarations {
  @Override
  public String identity() {
    return "common";
  }

  @Override
  public List<EventPublication> publications() {
    return List.of(
        ResourceLifecycleEvents.COMPLETION_PUBLICATION,
        ApplicationCatalogRemovalEvents.ACK,
        CredentialAuditContract.PUBLICATION,
        MemberLabelEvents.PUBLICATION);
  }

  @Override
  public List<EventSubscription> subscriptions() {
    return List.of(ResourceLifecycleEvents.GRANTS_SUBSCRIPTION, ApplicationCatalogRemovalEvents.AUTHORIZATION);
  }
}
