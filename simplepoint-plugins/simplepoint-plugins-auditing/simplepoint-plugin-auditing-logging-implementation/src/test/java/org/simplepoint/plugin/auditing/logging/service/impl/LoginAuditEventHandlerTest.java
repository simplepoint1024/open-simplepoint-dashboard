package org.simplepoint.plugin.auditing.logging.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.plugin.auditing.logging.api.entity.LoginLog;
import org.simplepoint.plugin.auditing.logging.api.event.LoginAuditContract;
import org.simplepoint.plugin.auditing.logging.api.pojo.command.LoginLogRecordCommand;
import org.simplepoint.plugin.auditing.logging.api.service.LoginLogService;

class LoginAuditEventHandlerTest {
  LoginLogService logs = mock(LoginLogService.class);
  LoginAuditEventHandler handler = new LoginAuditEventHandler(logs);
  LoginLogRecordCommand command() {
    var command = new LoginLogRecordCommand(); command.setStatus("SUCCESS");
    command.setSourceService("authorization"); command.setUserId("alice");
    command.setUsername("Alice"); command.setLoginAt(Instant.now()); return command;
  }
  EventMessage event(LoginLogRecordCommand command, String producer) {
    return new EventMessage(UUID.randomUUID(), LoginAuditContract.EVENT_TYPE, 1, producer,
        "platform", "authentication", 0, "operation", "correlation", command.getLoginAt(), LoginAuditContract.encode(command));
  }
  @Test void insertsTheValidatedIdentityAndPropagatesStorageFailureForInboxRetry() {
    var command = command(); handler.handle(event(command, "authorization"));
    verify(logs).create(argThat((LoginLog log) -> log.getUserId().equals("alice")
        && log.getLoginAt().equals(command.getLoginAt()) && log.getTenantId() == null));
    when(logs.create(any(LoginLog.class))).thenThrow(new IllegalStateException("database failure"));
    assertThatThrownBy(() -> handler.handle(event(command, "authorization"))).isInstanceOf(IllegalStateException.class);
  }
  @Test void rejectsOtherProducersRawSessionCredentialsAndUnexpectedFields() {
    var command = command();
    assertThatThrownBy(() -> handler.handle(event(command, "other"))).isInstanceOf(IllegalArgumentException.class);
    command.setSessionId("live-session-cookie");
    assertThatThrownBy(() -> LoginAuditContract.encode(command)).isInstanceOf(IllegalArgumentException.class);
    command.setSessionId(null);
    var payload = (com.fasterxml.jackson.databind.node.ObjectNode) LoginAuditContract.encode(command);
    payload.put("password", "must-not-be-accepted");
    assertThatThrownBy(() -> LoginAuditContract.decode(payload)).isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(logs);
  }
}
