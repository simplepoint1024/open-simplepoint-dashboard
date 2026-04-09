/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.logging.service.impl;

import java.time.Instant;
import org.simplepoint.data.amqp.annotation.AmqpRemoteService;
import org.simplepoint.plugin.auditing.logging.api.entity.LoginLog;
import org.simplepoint.plugin.auditing.logging.api.pojo.command.LoginLogRecordCommand;
import org.simplepoint.plugin.auditing.logging.api.service.LoginLogRemoteService;
import org.simplepoint.plugin.auditing.logging.api.service.LoginLogService;

/**
 * Remote provider for login log recording.
 */
@AmqpRemoteService
public class LoginLogRemoteServiceImpl implements LoginLogRemoteService {

  private final LoginLogService loginLogService;

  /**
   * Creates the remote provider backed by the local login log service.
   *
   * @param loginLogService the login log service
   */
  public LoginLogRemoteServiceImpl(final LoginLogService loginLogService) {
    this.loginLogService = loginLogService;
  }

  @Override
  public void record(final LoginLogRecordCommand command) {
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
    loginLogService.create(loginLog);
  }
}
