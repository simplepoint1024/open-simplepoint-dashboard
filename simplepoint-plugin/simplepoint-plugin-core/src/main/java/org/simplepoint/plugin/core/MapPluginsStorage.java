/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.simplepoint.api.data.Storage;
import org.simplepoint.plugin.api.Plugin;

/**
 * An implementation of the Storage interface for managing plugins.
 * This class uses a HashMap to store and manage plugin instances,
 * providing methods for saving, removing, finding, and listing plugins.
 */
public class MapPluginsStorage implements Storage<Plugin> {

  private final Map<String, Plugin> plugins = new HashMap<>();

  /**
   * Saves a plugin instance to the storage.
   * The plugin is stored using its manifest id as the key.
   *
   * @param plugin the plugin instance to save
   * @return the saved plugin instance
   */
  @Override
  public Plugin save(Plugin plugin) {
    plugins.put(plugin.manifest().getId(), plugin);
    return plugin;
  }

  /**
   * Removes a plugin instance from the storage by its manifest id.
   *
   * @param pluginId the manifest id of the plugin to remove
   */
  @Override
  public void remove(String pluginId) {
    plugins.remove(pluginId);
  }

  /**
   * Finds a plugin instance in the storage by its manifest id.
   *
   * @param pluginId the manifest id of the plugin to find
   * @return the plugin instance associated with the given manifest id, or null if not found
   */
  @Override
  public Plugin find(String pluginId) {
    return plugins.get(pluginId);
  }

  /**
   * Lists all plugin instances currently stored.
   *
   * @return a list of all stored plugin instances
   */
  @Override
  public List<Plugin> list() {
    return plugins.values().stream().toList();
  }
}
