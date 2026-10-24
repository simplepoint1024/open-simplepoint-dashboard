package org.simplepoint.plugin.auditing.logging.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.plugin.auditing.logging.api.entity.LoginLog;
import org.simplepoint.plugin.auditing.logging.api.event.CredentialAuditContract;
import org.simplepoint.plugin.auditing.logging.api.service.LoginLogService;

class CredentialAuditEventHandlerTest {
  private final LoginLogService logs = mock(LoginLogService.class);
  private final CredentialAuditEventHandler handler = new CredentialAuditEventHandler(logs);

  @Test
  void mapsSafeIdentityAndPropagatesPersistenceFailureForInboxRetry() {
    var event = event("common", "credential-authentication");
    handler.handle(event);
    verify(logs)
        .create(
            argThat(
                (LoginLog log) ->
                    "alice".equals(log.getUserId())
                        && "SUCCESS".equals(log.getStatus())
                        && "SERVICE_CREDENTIAL".equals(log.getAuthenticationType())
                        && "common".equals(log.getSourceService())
                        && log.getSessionId() == null
                        && log.getTenantId() == null));
    when(logs.create(any(LoginLog.class)))
        .thenThrow(new IllegalStateException("database unavailable"));
    assertThatThrownBy(() -> handler.handle(event)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void rejectsForgedEnvelopeAndUnexpectedSensitivePayloadFields() {
    assertThatThrownBy(() -> handler.handle(event("authorization", "credential-authentication")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> handler.handle(event("common", "other-resource")))
        .isInstanceOf(IllegalArgumentException.class);
    var event = event("common", "credential-authentication");
    var payload = (com.fasterxml.jackson.databind.node.ObjectNode) event.payload();
    payload.put("password", "must-not-be-accepted");
    var invalid =
        new EventMessage(
            event.eventId(),
            event.eventType(),
            event.schemaVersion(),
            event.producer(),
            event.tenantId(),
            event.resourceId(),
            event.generation(),
            event.operationId(),
            event.correlationId(),
            event.occurredAt(),
            payload);
    assertThatThrownBy(() -> handler.handle(invalid)).isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(logs);
  }

  private static EventMessage event(String producer, String resource) {
    var now = Instant.ofEpochMilli(Instant.now().toEpochMilli());
    var payload =
        CredentialAuditContract.encode(
            new CredentialAuditContract.Outcome(
                "dna", "alice", "a".repeat(64), "SUCCESS", "VERIFIED", now.toEpochMilli()));
    return new EventMessage(
        UUID.randomUUID(),
        CredentialAuditContract.PUBLICATION.eventType(),
        1,
        producer,
        "platform",
        resource,
        0,
        "operation",
        "correlation",
        now,
        payload);
  }
}
