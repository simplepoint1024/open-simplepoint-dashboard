package org.simplepoint.security.authorization.audit;

import java.util.List;
import org.simplepoint.events.api.EventDeclarations;
import org.simplepoint.events.api.EventPublication;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.plugin.auditing.logging.api.event.LoginAuditContract;

/** Preserves the historical login-audit producer identity when relocating the adapter. */
public final class AuthorizationEventDeclarations implements EventDeclarations {
  @Override
  public String identity() {
    return "authorization";
  }

  @Override
  public List<EventPublication> publications() {
    return List.of(LoginAuditContract.PUBLICATION);
  }

  @Override
  public List<EventSubscription> subscriptions() {
    return List.of();
  }
}
