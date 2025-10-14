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
   * The plugin is stored using its package name as the key.
   *
   * @param plugin the plugin instance to save
   * @return the saved plugin instance
   */
  @Override
  public Plugin save(Plugin plugin) {
    plugins.put(plugin.metadata().getPackageName(), plugin);
    return plugin;
  }

  /**
   * Removes a plugin instance from the storage by its package name.
   *
   * @param packageName the package name of the plugin to remove
   */
  @Override
  public void remove(String packageName) {
    plugins.remove(packageName);
  }

  /**
   * Finds a plugin instance in the storage by its package name.
   *
   * @param packageName the package name of the plugin to find
   * @return the plugin instance associated with the given package name, or null if not found
   */
  @Override
  public Plugin find(String packageName) {
    return plugins.get(packageName);
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
