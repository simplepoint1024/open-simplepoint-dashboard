package org.simplepoint.plugin.auditing.logging.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.auditing.logging.api.entity.ErrorLog;
import org.simplepoint.plugin.auditing.logging.api.entity.LoginLog;
import org.simplepoint.plugin.auditing.logging.api.entity.PermissionChangeLog;
import org.simplepoint.plugin.auditing.logging.api.pojo.command.ErrorLogRecordCommand;
import org.simplepoint.plugin.auditing.logging.api.pojo.command.LoginLogRecordCommand;
import org.simplepoint.plugin.auditing.logging.api.pojo.command.PermissionChangeLogRecordCommand;

class AuditingLoggingEntitiesTest {

  // ---- ErrorLog ----

  @Test
  void errorLog_setterGetter() {
    ErrorLog log = new ErrorLog();
    Instant now = Instant.now();
    log.setOccurredAt(now);
    log.setLevel("ERROR");
    log.setSourceService("common");
    log.setLoggerName("org.simplepoint.Test");
    log.setMessage("Something went wrong");
    log.setExceptionType("NullPointerException");
    log.setExceptionMessage("null");
    log.setTenantId("t1");
    log.setUserId("u1");
    log.setRequestUri("/api/test");
    log.setClientIp("127.0.0.1");
    assertThat(log.getOccurredAt()).isEqualTo(now);
    assertThat(log.getLevel()).isEqualTo("ERROR");
    assertThat(log.getSourceService()).isEqualTo("common");
    assertThat(log.getMessage()).isEqualTo("Something went wrong");
    assertThat(log.getExceptionType()).isEqualTo("NullPointerException");
    assertThat(log.getTenantId()).isEqualTo("t1");
    assertThat(log.getUserId()).isEqualTo("u1");
    assertThat(log.getClientIp()).isEqualTo("127.0.0.1");
  }

  // ---- LoginLog ----

  @Test
  void loginLog_setterGetter() {
    LoginLog log = new LoginLog();
    Instant now = Instant.now();
    log.setLoginAt(now);
    log.setStatus("SUCCESS");
    log.setLoginType("PASSWORD");
    log.setUsername("admin");
    log.setDisplayName("Administrator");
    log.setTenantId("t1");
    log.setClientIp("10.0.0.1");
    log.setRequestUri("/auth/login");
    assertThat(log.getLoginAt()).isEqualTo(now);
    assertThat(log.getStatus()).isEqualTo("SUCCESS");
    assertThat(log.getLoginType()).isEqualTo("PASSWORD");
    assertThat(log.getUsername()).isEqualTo("admin");
    assertThat(log.getDisplayName()).isEqualTo("Administrator");
    assertThat(log.getTenantId()).isEqualTo("t1");
    assertThat(log.getClientIp()).isEqualTo("10.0.0.1");
  }

  // ---- PermissionChangeLog ----

  @Test
  void permissionChangeLog_setterGetter() {
    PermissionChangeLog log = new PermissionChangeLog();
    log.setOperatorId("op1");
    log.setSubjectId("subj1");
    log.setChangeType("GRANT");
    log.setAction("ADD");
    assertThat(log.getOperatorId()).isEqualTo("op1");
    assertThat(log.getSubjectId()).isEqualTo("subj1");
    assertThat(log.getChangeType()).isEqualTo("GRANT");
    assertThat(log.getAction()).isEqualTo("ADD");
  }
}
