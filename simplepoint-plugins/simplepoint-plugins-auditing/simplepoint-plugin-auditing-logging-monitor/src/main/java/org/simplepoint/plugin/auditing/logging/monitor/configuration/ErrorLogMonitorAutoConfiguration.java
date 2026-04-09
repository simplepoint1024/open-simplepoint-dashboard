/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.logging.monitor.configuration;

import ch.qos.logback.classic.LoggerContext;
import org.simplepoint.plugin.auditing.logging.api.service.ErrorLogRemoteService;
import org.simplepoint.plugin.auditing.logging.monitor.logback.ErrorLogReportingAppender;
import org.simplepoint.plugin.auditing.logging.monitor.properties.ErrorLogMonitorProperties;
import org.simplepoint.plugin.auditing.logging.monitor.support.ErrorLogCommandFactory;
import org.simplepoint.plugin.auditing.logging.monitor.support.ErrorLogMonitorInstaller;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Auto-configuration for the log monitor that forwards warning and error logs to the auditing service.
 */
@AutoConfiguration
@EnableConfigurationProperties(ErrorLogMonitorProperties.class)
@ConditionalOnClass(LoggerContext.class)
@ConditionalOnBean(ErrorLogRemoteService.class)
@ConditionalOnProperty(prefix = ErrorLogMonitorProperties.PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
public class ErrorLogMonitorAutoConfiguration {

  /**
   * Creates the command factory that maps Logback events into audit commands.
   *
   * @param properties  monitor properties
   * @param environment spring environment
   * @return the command factory
   */
  @Bean
  @ConditionalOnMissingBean
  public ErrorLogCommandFactory errorLogCommandFactory(
      final ErrorLogMonitorProperties properties,
      final Environment environment
  ) {
    return new ErrorLogCommandFactory(properties, environment);
  }

  /**
   * Creates the reporting appender that forwards warning and error logs through AMQP.
   *
   * @param errorLogRemoteService the remote service proxy
   * @param errorLogCommandFactory the command factory
   * @return the reporting appender
   */
  @Bean
  @ConditionalOnMissingBean
  public ErrorLogReportingAppender errorLogReportingAppender(
      final ErrorLogRemoteService errorLogRemoteService,
      final ErrorLogCommandFactory errorLogCommandFactory
  ) {
    return new ErrorLogReportingAppender(errorLogRemoteService, errorLogCommandFactory);
  }

  /**
   * Creates the installer that attaches the reporting appender to the root logger.
   *
   * @param errorLogReportingAppender the reporting appender
   * @return the installer
   */
  @Bean
  @ConditionalOnMissingBean
  public ErrorLogMonitorInstaller errorLogMonitorInstaller(final ErrorLogReportingAppender errorLogReportingAppender) {
    return new ErrorLogMonitorInstaller(errorLogReportingAppender);
  }
}
