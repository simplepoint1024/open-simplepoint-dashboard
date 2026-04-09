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
 * Command for recording permission change logs.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PermissionChangeLogRecordCommand {
  private Instant changedAt;
  private String changeType;
  private String action;
  private String subjectType;
  private String subjectId;
  private String subjectLabel;
  private String targetType;
  private String targetSummary;
  private Integer targetCount;
  private String operatorId;
  private String tenantId;
  private String contextId;
  private String sourceService;
  private String description;
}
