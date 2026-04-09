/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.logging.api.service;

import org.simplepoint.data.amqp.annotation.AmqpRemoteClient;
import org.simplepoint.plugin.auditing.logging.api.pojo.command.LoginLogRecordCommand;

/**
 * Remote service used by the authorization service to record login logs.
 */
@AmqpRemoteClient(to = "auditing.login-log")
public interface LoginLogRemoteService {
  /**
   * Records a login log event in the auditing service.
   *
   * @param command the login log record payload
   */
  void record(LoginLogRecordCommand command);
}
