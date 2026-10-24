package org.simplepoint.plugin.auditing.logging.module;

import java.util.List;
import org.simplepoint.events.api.EventDeclarations;
import org.simplepoint.events.api.EventPublication;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.plugin.auditing.logging.api.event.CredentialAuditContract;
import org.simplepoint.plugin.auditing.logging.api.event.LoginAuditContract;

/** Stable domain event contracts, independent of deployment host names. */
public final class AuditEventDeclarations implements EventDeclarations {
  @Override
  public String identity() {
    return "auditing";
  }

  @Override
  public List<EventPublication> publications() {
    return List.of();
  }

  @Override
  public List<EventSubscription> subscriptions() {
    return List.of(LoginAuditContract.SUBSCRIPTION, CredentialAuditContract.SUBSCRIPTION);
  }
}
