/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.logging.api.service;

import org.simplepoint.plugin.auditing.logging.api.pojo.command.ErrorLogRecordCommand;
import org.simplepoint.remoting.RemoteContract;

/**
 * Remote service used to record warning and error log events.
 */
@RemoteContract(name = "auditing.error-log")
public interface ErrorLogRemoteService {
  /**
   * Records an error log event in the auditing service.
   *
   * @param command the error log record payload
   */
  void record(ErrorLogRecordCommand command);
}
