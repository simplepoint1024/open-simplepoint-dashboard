package org.simplepoint.plugin.auditing.logging.service.impl;

import java.time.Instant;
import org.simplepoint.events.api.EventHandler;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.plugin.auditing.logging.api.event.CredentialAuditContract;
import org.simplepoint.plugin.auditing.logging.api.pojo.command.LoginLogRecordCommand;
import org.simplepoint.plugin.auditing.logging.api.service.LoginLogService;
import org.springframework.stereotype.Component;

/** Persists identity-owned credential outcomes inside the platform inbox transaction. */
@Component
public class CredentialAuditEventHandler implements EventHandler {
  private final LoginLogService logs;

  /** Reuses the existing audit log persistence and permission model. */
  public CredentialAuditEventHandler(LoginLogService logs) {
    this.logs = logs;
  }

  @Override
  public EventSubscription subscription() {
    return CredentialAuditContract.SUBSCRIPTION;
  }

  @Override
  public void handle(EventMessage event) {
    subscription().validate(event);
    var outcome = CredentialAuditContract.decode(event.payload());
    if (!"platform".equals(event.tenantId())
        || !"credential-authentication".equals(event.resourceId())
        || event.generation() != 0
        || event.occurredAt().toEpochMilli() != outcome.occurredAt()) {
      throw new IllegalArgumentException("Invalid credential audit envelope");
    }
    var command = new LoginLogRecordCommand();
    command.setStatus(outcome.status());
    command.setLoginType("CREDENTIAL");
    command.setAuthenticationType("SERVICE_CREDENTIAL");
    command.setSourceService(CredentialAuditContract.PRODUCER);
    command.setUserId(outcome.userId());
    command.setUsername(
        outcome.userId() == null ? "sha256:" + outcome.subjectFingerprint() : outcome.userId());
    command.setDisplayName(command.getUsername());
    command.setRequestUri(
        "rpc://"
            + java.net.URLEncoder.encode(outcome.caller(), java.nio.charset.StandardCharsets.UTF_8)
            + "/security.credentials");
    command.setFailureReason("FAILURE".equals(outcome.status()) ? outcome.reason() : null);
    command.setLoginAt(Instant.ofEpochMilli(outcome.occurredAt()));
    logs.create(LoginAuditRecordMapper.toEntity(command));
  }
}
