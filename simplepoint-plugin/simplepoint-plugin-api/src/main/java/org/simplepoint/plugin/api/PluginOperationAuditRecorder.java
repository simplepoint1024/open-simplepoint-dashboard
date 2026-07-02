/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.api;

import java.util.List;
import org.simplepoint.plugin.api.management.PluginOperationAudit;

/**
 * Records plugin management operation audit entries.
 */
public interface PluginOperationAuditRecorder {

  /**
   * Records an audit entry.
   *
   * @param audit audit entry
   */
  void record(PluginOperationAudit audit);

  /**
   * Returns recorded audit entries visible to the management API.
   *
   * @return audit entries
   */
  default List<PluginOperationAudit> list() {
    return List.of();
  }
}
