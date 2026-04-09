/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.logging.api.service;

import org.simplepoint.data.amqp.annotation.AmqpRemoteClient;
import org.simplepoint.plugin.auditing.logging.api.pojo.command.PermissionChangeLogRecordCommand;

/**
 * Remote service used by the common service to record permission change logs.
 */
@AmqpRemoteClient(to = "auditing.permission-change-log")
public interface PermissionChangeLogRemoteService {
  /**
   * Records a permission change event in the auditing service.
   *
   * @param command the permission change record payload
   */
  void record(PermissionChangeLogRecordCommand command);
}
