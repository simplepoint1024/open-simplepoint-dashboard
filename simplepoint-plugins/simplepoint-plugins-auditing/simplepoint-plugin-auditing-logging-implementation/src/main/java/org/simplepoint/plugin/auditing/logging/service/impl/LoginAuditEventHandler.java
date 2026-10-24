package org.simplepoint.plugin.auditing.logging.service.impl;

import org.simplepoint.events.api.EventHandler;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.plugin.auditing.logging.api.event.LoginAuditContract;
import org.simplepoint.plugin.auditing.logging.api.service.LoginLogService;
import org.springframework.stereotype.Component;

/** Inbox completion and the local log insert share the event worker's database transaction. */
@Component
public class LoginAuditEventHandler implements EventHandler {
  private final LoginLogService logs;

  /** Creates the consumer using only the local audit service. */
  public LoginAuditEventHandler(LoginLogService logs) {
    this.logs = logs;
  }

  @Override
  public EventSubscription subscription() {
    return LoginAuditContract.SUBSCRIPTION;
  }

  @Override
  public void handle(EventMessage event) {
    subscription().validate(event);
    if (!"platform".equals(event.tenantId())
        || !"authentication".equals(event.resourceId())
        || event.generation() != 0) {
      throw new IllegalArgumentException("Invalid login audit event scope");
    }
    var command = LoginAuditContract.decode(event.payload(), event.occurredAt());
    logs.create(LoginAuditRecordMapper.toEntity(command));
  }
}
