package org.simplepoint.plugin.auditing.logging.service.impl;

import java.time.Instant;
import org.simplepoint.plugin.auditing.logging.api.entity.LoginLog;
import org.simplepoint.plugin.auditing.logging.api.pojo.command.LoginLogRecordCommand;

/** Local mapping only. Login audit writes enter exclusively through the trusted durable inbox. */
public final class LoginAuditRecordMapper {
  private LoginAuditRecordMapper() {}

  /** Maps the validated command to a local audit row without remote effects. */
  public static LoginLog toEntity(LoginLogRecordCommand command) {
    LoginLog loginLog = new LoginLog();
    loginLog.setLoginAt(command.getLoginAt() == null ? Instant.now() : command.getLoginAt());
    loginLog.setStatus(command.getStatus());
    loginLog.setLoginType(command.getLoginType());
    loginLog.setUsername(command.getUsername());
    loginLog.setDisplayName(command.getDisplayName());
    loginLog.setUserId(command.getUserId());
    loginLog.setTenantId(command.getTenantId());
    loginLog.setContextId(command.getContextId());
    loginLog.setClientIp(command.getClientIp());
    loginLog.setRemoteAddress(command.getRemoteAddress());
    loginLog.setForwardedFor(command.getForwardedFor());
    loginLog.setUserAgent(command.getUserAgent());
    loginLog.setRequestUri(command.getRequestUri());
    loginLog.setSessionId(command.getSessionId());
    loginLog.setAuthenticationType(command.getAuthenticationType());
    loginLog.setSourceService(command.getSourceService());
    loginLog.setFailureReason(command.getFailureReason());
    return loginLog;
  }
}
