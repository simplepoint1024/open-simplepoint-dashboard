/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.logging.service.impl;

import java.time.Instant;
import org.simplepoint.plugin.auditing.logging.api.entity.ResourceGrantLog;
import org.simplepoint.plugin.auditing.logging.api.pojo.command.ResourceGrantLogRecordCommand;
import org.simplepoint.plugin.auditing.logging.api.service.ResourceGrantLogRemoteService;
import org.simplepoint.plugin.auditing.logging.api.service.ResourceGrantLogService;
import org.simplepoint.remoting.RemoteProvider;
import org.springframework.stereotype.Service;

/**
 * Remote provider for resource grant log recording.
 */
@Service
@RemoteProvider
public class ResourceGrantLogRemoteServiceImpl implements ResourceGrantLogRemoteService {

  private final ResourceGrantLogService resourceGrantLogService;

  /**
   * Creates the remote provider backed by the local resource grant log service.
   *
   * @param resourceGrantLogService the resource grant log service
   */
  public ResourceGrantLogRemoteServiceImpl(final ResourceGrantLogService resourceGrantLogService) {
    this.resourceGrantLogService = resourceGrantLogService;
  }

  @Override
  public void record(final ResourceGrantLogRecordCommand command) {
    ResourceGrantLog resourceGrantLog = new ResourceGrantLog();
    resourceGrantLog.setChangedAt(command.getChangedAt() == null ? Instant.now() : command.getChangedAt());
    resourceGrantLog.setChangeType(command.getChangeType());
    resourceGrantLog.setAction(command.getAction());
    resourceGrantLog.setSubjectType(command.getSubjectType());
    resourceGrantLog.setSubjectId(command.getSubjectId());
    resourceGrantLog.setSubjectLabel(command.getSubjectLabel());
    resourceGrantLog.setTargetType(command.getTargetType());
    resourceGrantLog.setTargetSummary(command.getTargetSummary());
    resourceGrantLog.setTargetCount(command.getTargetCount());
    resourceGrantLog.setOperatorId(command.getOperatorId());
    resourceGrantLog.setTenantId(command.getTenantId());
    resourceGrantLog.setContextId(command.getContextId());
    resourceGrantLog.setSourceService(command.getSourceService());
    resourceGrantLog.setDescription(command.getDescription());
    resourceGrantLogService.create(resourceGrantLog);
  }
}
