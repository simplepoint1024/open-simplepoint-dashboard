/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.simplepoint.plugin.api.PluginInstanceHandler;

/**
 * A context holder class for managing plugin instance handlers.
 * This class provides methods to register, unregister, and retrieve plugin handlers
 * associated with specific groups.
 */
public class PluginContextHolder {

  // A map storing plugin instance handlers, categorized by group names.
  private static final Map<String, PluginInstanceHandler> SUBPROCESSES = new HashMap<>();

  /**
   * Registers a plugin instance handler.
   * This method associates the handler with its respective groups
   * by adding entries to the SUBPROCESSES map.
   *
   * @param processes the PluginInstanceHandler to register
   */
  public static void register(PluginInstanceHandler processes) {
    processes.groups().forEach(group -> SUBPROCESSES.put(group, processes));
  }

  /**
   * Unregisters a plugin instance handler for the specified group.
   *
   * @param group the group name whose handler should be removed
   */
  public static void unregister(String group) {
    SUBPROCESSES.remove(group);
  }

  /**
   * Retrieves an unmodifiable view of all registered plugin instance handlers.
   *
   * @return an unmodifiable map of group names and their associated plugin handlers
   */
  public static Map<String, PluginInstanceHandler> getHeaders() {
    return Collections.unmodifiableMap(SUBPROCESSES);
  }
}
