/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.logging.monitor.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.simplepoint.plugin.auditing.logging.api.pojo.command.ErrorLogRecordCommand;
import org.simplepoint.plugin.auditing.logging.api.service.ErrorLogRemoteService;
import org.simplepoint.plugin.auditing.logging.monitor.support.ErrorLogCommandFactory;

/**
 * Logback appender that reports warning and error log events to the auditing service.
 */
public class ErrorLogReportingAppender extends AppenderBase<ILoggingEvent> {

  private static final ThreadLocal<Boolean> REPORTING = ThreadLocal.withInitial(() -> false);
  private static final String MONITOR_PACKAGE_PREFIX = "org.simplepoint.plugin.auditing.logging.monitor";

  private final ErrorLogRemoteService errorLogRemoteService;
  private final ErrorLogCommandFactory commandFactory;

  /**
   * Creates the appender with the remote service and command factory it delegates to.
   *
   * @param errorLogRemoteService the remote service used to record log events
   * @param commandFactory        the command factory used to map Logback events
   */
  public ErrorLogReportingAppender(
      final ErrorLogRemoteService errorLogRemoteService,
      final ErrorLogCommandFactory commandFactory
  ) {
    this.errorLogRemoteService = errorLogRemoteService;
    this.commandFactory = commandFactory;
  }

  @Override
  protected void append(final ILoggingEvent eventObject) {
    if (eventObject == null || REPORTING.get() || !shouldCapture(eventObject) || shouldIgnore(eventObject)) {
      return;
    }
    try {
      REPORTING.set(true);
      ErrorLogRecordCommand command = commandFactory.create(eventObject);
      if (command.getMessage() == null && command.getExceptionMessage() == null && command.getStackTrace() == null) {
        return;
      }
      errorLogRemoteService.record(command);
    } catch (RuntimeException ex) {
      addError("Failed to report log event to auditing service", ex);
    } finally {
      REPORTING.remove();
    }
  }

  boolean shouldCapture(final ILoggingEvent eventObject) {
    return eventObject.getThrowableProxy() != null
        || (eventObject.getLevel() != null && eventObject.getLevel().levelInt >= Level.WARN_INT);
  }

  private boolean shouldIgnore(final ILoggingEvent eventObject) {
    String loggerName = eventObject.getLoggerName();
    return loggerName != null && loggerName.startsWith(MONITOR_PACKAGE_PREFIX);
  }
}
