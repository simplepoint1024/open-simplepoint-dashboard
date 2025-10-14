/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.api;

import java.util.List;

/**
 * Defines the handler interface for managing plugin instances.
 * This interface includes methods for creating, handling, hooking,
 * and rolling back plugin instances.
 */
public interface PluginInstanceHandler {

  /**
   * Retrieves the list of supported plugin groups.
   *
   * @return a list of group names associated with the plugin instances
   */
  List<String> groups();

  /**
   * Handles the provided plugin instance.
   * This method processes the plugin instance according to its configuration or
   * lifecycle requirements.
   *
   * @param instance the plugin instance to handle
   */
  void handle(Plugin.PluginInstance instance);

  /**
   * Rolls back changes or operations made by the provided plugin instance.
   *
   * @param instance the plugin instance to roll back
   */
  void rollback(Plugin.PluginInstance instance);
}
