/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.api;

import java.util.List;
import org.simplepoint.plugin.api.management.PluginTaskSnapshot;

/**
 * Stores plugin runtime task snapshots.
 *
 * <p>The default implementation is in-memory. Production deployments can
 * replace it with a JDBC, Redis, or message-backed implementation to keep
 * task state visible across process restarts and cluster nodes.
 */
public interface PluginTaskStore {

  /**
   * Saves or updates a task snapshot.
   *
   * @param snapshot task snapshot
   */
  void save(PluginTaskSnapshot snapshot);

  /**
   * Lists stored task snapshots.
   *
   * @return task snapshots
   */
  List<PluginTaskSnapshot> list();
}
