/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.logging.monitor.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Properties for the automatic error log monitor.
 */
@Data
@ConfigurationProperties(prefix = ErrorLogMonitorProperties.PREFIX)
public class ErrorLogMonitorProperties {
  public static final String PREFIX = "simplepoint.auditing.log-monitor";

  private boolean enabled = true;
  private String sourceService;
  private Integer messageMaxLength = 4000;
  private Integer exceptionMessageMaxLength = 4000;
  private Integer stackTraceMaxLength = 16000;
}
