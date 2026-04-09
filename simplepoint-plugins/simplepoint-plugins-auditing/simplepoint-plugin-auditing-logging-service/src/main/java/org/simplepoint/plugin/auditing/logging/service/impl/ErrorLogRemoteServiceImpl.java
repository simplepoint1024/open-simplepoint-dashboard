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
import org.simplepoint.plugin.auditing.logging.api.entity.ErrorLog;
import org.simplepoint.plugin.auditing.logging.api.pojo.command.ErrorLogRecordCommand;
import org.simplepoint.plugin.auditing.logging.api.service.ErrorLogRemoteService;
import org.simplepoint.plugin.auditing.logging.api.service.ErrorLogService;

/**
 * Remote provider for error log recording.
 */
@AmqpRemoteService
public class ErrorLogRemoteServiceImpl implements ErrorLogRemoteService {

  private final ErrorLogService errorLogService;

  /**
   * Creates the remote provider backed by the local error log service.
   *
   * @param errorLogService the error log service
   */
  public ErrorLogRemoteServiceImpl(final ErrorLogService errorLogService) {
    this.errorLogService = errorLogService;
  }

  @Override
  public void record(final ErrorLogRecordCommand command) {
    ErrorLog errorLog = new ErrorLog();
    errorLog.setOccurredAt(command.getOccurredAt() == null ? Instant.now() : command.getOccurredAt());
    errorLog.setLevel(command.getLevel());
    errorLog.setSourceService(command.getSourceService());
    errorLog.setLoggerName(command.getLoggerName());
    errorLog.setThreadName(command.getThreadName());
    errorLog.setMessage(command.getMessage());
    errorLog.setExceptionType(command.getExceptionType());
    errorLog.setExceptionMessage(command.getExceptionMessage());
    errorLog.setStackTrace(command.getStackTrace());
    errorLog.setTenantId(command.getTenantId());
    errorLog.setContextId(command.getContextId());
    errorLog.setUserId(command.getUserId());
    errorLog.setRequestUri(command.getRequestUri());
    errorLog.setClientIp(command.getClientIp());
    errorLogService.create(errorLog);
  }
}
