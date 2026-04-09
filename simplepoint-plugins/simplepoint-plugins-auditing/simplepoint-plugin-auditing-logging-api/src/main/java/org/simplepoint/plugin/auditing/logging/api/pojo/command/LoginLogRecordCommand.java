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
 * Command for recording login logs.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginLogRecordCommand {
  private String status;
  private String loginType;
  private String username;
  private String displayName;
  private String userId;
  private String tenantId;
  private String contextId;
  private String clientIp;
  private String remoteAddress;
  private String forwardedFor;
  private String userAgent;
  private String requestUri;
  private String sessionId;
  private String authenticationType;
  private String sourceService;
  private String failureReason;
  private Instant loginAt;
}
