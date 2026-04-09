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
import org.simplepoint.plugin.auditing.logging.api.entity.PermissionChangeLog;
import org.simplepoint.plugin.auditing.logging.api.pojo.command.PermissionChangeLogRecordCommand;
import org.simplepoint.plugin.auditing.logging.api.service.PermissionChangeLogRemoteService;
import org.simplepoint.plugin.auditing.logging.api.service.PermissionChangeLogService;

/**
 * Remote provider for permission change log recording.
 */
@AmqpRemoteService
public class PermissionChangeLogRemoteServiceImpl implements PermissionChangeLogRemoteService {

  private final PermissionChangeLogService permissionChangeLogService;

  /**
   * Creates the remote provider backed by the local permission change log service.
   *
   * @param permissionChangeLogService the permission change log service
   */
  public PermissionChangeLogRemoteServiceImpl(final PermissionChangeLogService permissionChangeLogService) {
    this.permissionChangeLogService = permissionChangeLogService;
  }

  @Override
  public void record(final PermissionChangeLogRecordCommand command) {
    PermissionChangeLog permissionChangeLog = new PermissionChangeLog();
    permissionChangeLog.setChangedAt(command.getChangedAt() == null ? Instant.now() : command.getChangedAt());
    permissionChangeLog.setChangeType(command.getChangeType());
    permissionChangeLog.setAction(command.getAction());
    permissionChangeLog.setSubjectType(command.getSubjectType());
    permissionChangeLog.setSubjectId(command.getSubjectId());
    permissionChangeLog.setSubjectLabel(command.getSubjectLabel());
    permissionChangeLog.setTargetType(command.getTargetType());
    permissionChangeLog.setTargetSummary(command.getTargetSummary());
    permissionChangeLog.setTargetCount(command.getTargetCount());
    permissionChangeLog.setOperatorId(command.getOperatorId());
    permissionChangeLog.setTenantId(command.getTenantId());
    permissionChangeLog.setContextId(command.getContextId());
    permissionChangeLog.setSourceService(command.getSourceService());
    permissionChangeLog.setDescription(command.getDescription());
    permissionChangeLogService.create(permissionChangeLog);
  }
}
