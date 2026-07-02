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
import org.simplepoint.plugin.api.management.PluginInstallPlan;
import org.simplepoint.plugin.api.management.PluginOperationAudit;
import org.simplepoint.plugin.api.management.PluginRegistryView;
import org.simplepoint.plugin.api.management.PluginTaskSnapshot;
import org.simplepoint.plugin.api.management.PluginUpgradePlan;
import org.simplepoint.plugin.api.manifest.PluginManifest;

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
   * Plans installation of all plugins from the specified directory without mutating runtime state.
   *
   * @param path the directory containing plugin archives
   * @return plugin installation plan
   */
  PluginInstallPlan planInstallAll(File path);

  /**
   * Installs a plugin from the specified URI.
   *
   * @param uri the URI of the plugin to be installed
   * @return the installed Plugin instance
   * @throws Exception if an error occurs during installation
   */
  Plugin install(URI uri) throws Exception;

  /**
   * Plans installation of a plugin archive without mutating runtime state.
   *
   * @param uri the URI of the plugin archive to plan
   * @return plugin installation plan
   */
  PluginInstallPlan planInstall(URI uri);

  /**
   * Upgrades an installed plugin from the specified URI.
   *
   * @param uri the URI of the new plugin archive
   * @return the upgraded Plugin instance
   * @throws Exception if the plugin is not installed or the upgrade fails
   */
  Plugin upgrade(URI uri) throws Exception;

  /**
   * Plans upgrade of an installed plugin without mutating runtime state.
   *
   * @param uri the URI of the new plugin archive to plan
   * @return plugin upgrade plan
   */
  PluginUpgradePlan planUpgrade(URI uri);

  /**
   * Enables a disabled plugin without reinstalling its artifact.
   *
   * @param pluginId the manifest id of the plugin to enable
   * @return the enabled plugin
   */
  Plugin enable(String pluginId);

  /**
   * Disables an enabled plugin without removing its artifact or class loader.
   *
   * @param pluginId the manifest id of the plugin to disable
   * @return the disabled plugin
   */
  Plugin disable(String pluginId);

  /**
   * Reads and validates a plugin manifest without installing the plugin.
   *
   * @param uri the URI of the plugin archive to inspect
   * @return the validated plugin manifest
   * @throws Exception if the archive cannot be read or the manifest is invalid
   */
  PluginManifest inspect(URI uri) throws Exception;

  /**
   * Returns a read model of the plugin registry.
   *
   * @return plugin registry view
   */
  PluginRegistryView registry();

  /**
   * Returns plugin operation audit entries.
   *
   * @return plugin operation audit entries
   */
  List<PluginOperationAudit> operationAudits();

  /**
   * Returns runtime registration task snapshots.
   *
   * @return plugin runtime task snapshots
   */
  List<PluginTaskSnapshot> operationTasks();

  /**
   * Executes tasks from the queue. If a task fails, it is retried up to 10 times.
   * If the maximum retry limit is reached, an exception is thrown.
   */
  void submit();

  /**
   * Uninstalls a plugin by its manifest id.
   *
   * @param pluginId the manifest id of the plugin to uninstall
   */
  void uninstall(String pluginId);

  /**
   * Registers a new PluginInstanceHandler for managing plugin instances.
   *
   * @param handle the PluginInstanceHandler to register
   */
  void registerHandle(PluginInstanceHandler handle);

  /**
   * Registers an install-time validator for plugin candidates.
   *
   * @param validator install validator
   */
  void registerInstallValidator(PluginInstallValidator validator);

  /**
   * Registers an install-time validator for plugin candidate batches.
   *
   * @param validator batch install validator
   */
  void registerInstallBatchValidator(PluginInstallBatchValidator validator);

  /**
   * Unregisters an install-time validator.
   *
   * @param validator install validator
   */
  void unregisterInstallValidator(PluginInstallValidator validator);

  /**
   * Unregisters an install-time batch validator.
   *
   * @param validator batch install validator
   */
  void unregisterInstallBatchValidator(PluginInstallBatchValidator validator);

  /**
   * Registers a plugin lifecycle handler for declarative manifest contributions.
   *
   * @param handler lifecycle handler
   */
  void registerLifecycleHandler(PluginLifecycleHandler handler);

  /**
   * Unregisters a plugin lifecycle handler.
   *
   * @param handler lifecycle handler
   */
  void unregisterLifecycleHandler(PluginLifecycleHandler handler);

  /**
   * Retrieves the storage used for managing plugins.
   *
   * @return the Storage instance containing plugin data
   */
  Storage<Plugin> getStorage();
}
