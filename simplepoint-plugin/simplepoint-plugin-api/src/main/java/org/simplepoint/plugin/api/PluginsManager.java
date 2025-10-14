/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.api;

import java.io.File;
import java.net.URI;
import java.util.List;
import org.simplepoint.api.data.Storage;

/**
 * Defines the interface for managing plugins in the system.
 * This interface provides methods to install, uninstall, and manage plugins,
 * as well as register handlers and access the plugin storage.
 */
public interface PluginsManager {

  /**
   * Installs all plugins from the specified directory.
   *
   * @param path the directory containing the plugins to be installed
   * @return a list of installed Plugin instances
   * @throws Exception if an error occurs during installation
   */
  List<Plugin> installAll(File path) throws Exception;

  /**
   * Installs a plugin from the specified URI.
   *
   * @param uri the URI of the plugin to be installed
   * @return the installed Plugin instance
   * @throws Exception if an error occurs during installation
   */
  Plugin install(URI uri) throws Exception;

  /**
   * Executes tasks from the queue. If a task fails, it is retried up to 10 times.
   * If the maximum retry limit is reached, an exception is thrown.
   */
  void submit();

  /**
   * Uninstalls a plugin by its package name.
   *
   * @param packageName the package name of the plugin to uninstall
   */
  void uninstall(String packageName);

  /**
   * Registers a new PluginInstanceHandler for managing plugin instances.
   *
   * @param handle the PluginInstanceHandler to register
   */
  void registerHandle(PluginInstanceHandler handle);

  /**
   * Retrieves the storage used for managing plugins.
   *
   * @return the Storage instance containing plugin data
   */
  Storage<Plugin> getStorage();
}
