/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.logging.api.pojo.command;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Command for recording warning, error and exception log events.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ErrorLogRecordCommand {
  private Instant occurredAt;
  private String level;
  private String sourceService;
  private String loggerName;
  private String threadName;
  private String message;
  private String exceptionType;
  private String exceptionMessage;
  private String stackTrace;
  private String tenantId;
  private String contextId;
  private String userId;
  private String requestUri;
  private String clientIp;
}
