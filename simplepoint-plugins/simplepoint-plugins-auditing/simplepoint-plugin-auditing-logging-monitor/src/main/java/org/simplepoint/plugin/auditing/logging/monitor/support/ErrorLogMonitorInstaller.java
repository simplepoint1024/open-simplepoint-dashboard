/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.logging.monitor.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.simplepoint.plugin.auditing.logging.monitor.logback.ErrorLogReportingAppender;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Installs the reporting appender onto the Logback root logger.
 */
public class ErrorLogMonitorInstaller implements SmartInitializingSingleton, DisposableBean {

  static final String APPENDER_NAME = "simplepointAuditErrorLogAppender";

  private final ErrorLogReportingAppender errorLogReportingAppender;

  /**
   * Creates the installer responsible for attaching the appender to the root logger.
   *
   * @param errorLogReportingAppender the reporting appender
   */
  public ErrorLogMonitorInstaller(final ErrorLogReportingAppender errorLogReportingAppender) {
    this.errorLogReportingAppender = errorLogReportingAppender;
  }

  @Override
  public void afterSingletonsInstantiated() {
    if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext loggerContext)) {
      return;
    }
    Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
    rootLogger.detachAppender(APPENDER_NAME);
    errorLogReportingAppender.setContext(loggerContext);
    errorLogReportingAppender.setName(APPENDER_NAME);
    if (!errorLogReportingAppender.isStarted()) {
      errorLogReportingAppender.start();
    }
    rootLogger.addAppender(errorLogReportingAppender);
  }

  @Override
  public void destroy() {
    if (LoggerFactory.getILoggerFactory() instanceof LoggerContext loggerContext) {
      Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
      rootLogger.detachAppender(APPENDER_NAME);
    }
    errorLogReportingAppender.stop();
  }
}
