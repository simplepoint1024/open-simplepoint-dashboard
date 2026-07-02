/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.core;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import org.simplepoint.api.data.Storage;
import org.simplepoint.core.ApplicationContextHolder;
import org.simplepoint.plugin.api.Plugin;
import org.simplepoint.plugin.api.PluginInstallBatchValidator;
import org.simplepoint.plugin.api.PluginInstallValidator;
import org.simplepoint.plugin.api.PluginInstanceHandler;
import org.simplepoint.plugin.api.PluginLifecycleHandler;
import org.simplepoint.plugin.api.PluginOperationAuditRecorder;
import org.simplepoint.plugin.api.PluginRuntimeCoordinator;
import org.simplepoint.plugin.api.PluginTaskStore;
import org.simplepoint.plugin.api.PluginsManager;
import org.simplepoint.plugin.api.management.PluginInstallPlan;
import org.simplepoint.plugin.api.management.PluginOperationAudit;
import org.simplepoint.plugin.api.management.PluginRegistryView;
import org.simplepoint.plugin.api.management.PluginTaskSnapshot;
import org.simplepoint.plugin.api.management.PluginUpgradePlan;
import org.simplepoint.plugin.api.manifest.PluginManifest;

/**
 * An abstract class providing a base implementation for the PluginsManager interface.
 * This class leverages PluginClassloader to manage plugin installation, uninstallation,
 * and registration. It also provides multiple constructors for flexibility in initialization.
 */
public abstract class AbstractPluginsManager implements PluginsManager {

  protected final PluginClassloader pluginClassloader;

  /**
   * Constructs a new AbstractPluginsManager instance with the specified parent class loader
   * and plugin storage.
   *
   * @param parentClassloader the parent class loader for the plugin class loader
   * @param pluginStorage     the storage for managing plugins
   * @throws MalformedURLException if an invalid URL is encountered
   * @throws URISyntaxException    if an invalid URI is encountered
   */
  public AbstractPluginsManager(ClassLoader parentClassloader, Storage<Plugin> pluginStorage)
      throws MalformedURLException, URISyntaxException {
    this.pluginClassloader = new PluginClassloader(parentClassloader, pluginStorage);
  }

  /**
   * Constructs a new AbstractPluginsManager instance with custom compatibility verification.
   *
   * @param parentClassloader     the parent class loader for the plugin class loader
   * @param pluginStorage         the storage for managing plugins
   * @param compatibilityVerifier plugin compatibility verifier
   * @throws MalformedURLException if an invalid URL is encountered
   * @throws URISyntaxException    if an invalid URI is encountered
   */
  public AbstractPluginsManager(
      ClassLoader parentClassloader,
      Storage<Plugin> pluginStorage,
      PluginCompatibilityVerifier compatibilityVerifier
  ) throws MalformedURLException, URISyntaxException {
    this.pluginClassloader = new PluginClassloader(parentClassloader, pluginStorage, compatibilityVerifier);
  }

  /**
   * Constructs a new AbstractPluginsManager instance with custom verification.
   *
   * @param parentClassloader     the parent class loader for the plugin class loader
   * @param pluginStorage         the storage for managing plugins
   * @param compatibilityVerifier plugin compatibility verifier
   * @param artifactVerifier      plugin artifact verifier
   * @throws MalformedURLException if an invalid URL is encountered
   * @throws URISyntaxException    if an invalid URI is encountered
   */
  public AbstractPluginsManager(
      ClassLoader parentClassloader,
      Storage<Plugin> pluginStorage,
      PluginCompatibilityVerifier compatibilityVerifier,
      PluginArtifactVerifier artifactVerifier
  ) throws MalformedURLException, URISyntaxException {
    this.pluginClassloader =
        new PluginClassloader(parentClassloader, pluginStorage, compatibilityVerifier, artifactVerifier);
  }

  /**
   * Constructs a new AbstractPluginsManager instance with custom verification and audit recording.
   *
   * @param parentClassloader     the parent class loader for the plugin class loader
   * @param pluginStorage         the storage for managing plugins
   * @param compatibilityVerifier plugin compatibility verifier
   * @param artifactVerifier      plugin artifact verifier
   * @param auditRecorder         plugin operation audit recorder
   * @throws MalformedURLException if an invalid URL is encountered
   * @throws URISyntaxException    if an invalid URI is encountered
   */
  public AbstractPluginsManager(
      ClassLoader parentClassloader,
      Storage<Plugin> pluginStorage,
      PluginCompatibilityVerifier compatibilityVerifier,
      PluginArtifactVerifier artifactVerifier,
      PluginOperationAuditRecorder auditRecorder
  ) throws MalformedURLException, URISyntaxException {
    this.pluginClassloader =
        new PluginClassloader(parentClassloader, pluginStorage, compatibilityVerifier, artifactVerifier, auditRecorder);
  }

  /**
   * Constructs a new AbstractPluginsManager instance with custom verification,
   * audit recording, and runtime coordination.
   *
   * @param parentClassloader     the parent class loader for the plugin class loader
   * @param pluginStorage         the storage for managing plugins
   * @param compatibilityVerifier plugin compatibility verifier
   * @param artifactVerifier      plugin artifact verifier
   * @param auditRecorder         plugin operation audit recorder
   * @param runtimeCoordinator    plugin runtime operation coordinator
   * @throws MalformedURLException if an invalid URL is encountered
   * @throws URISyntaxException    if an invalid URI is encountered
   */
  public AbstractPluginsManager(
      ClassLoader parentClassloader,
      Storage<Plugin> pluginStorage,
      PluginCompatibilityVerifier compatibilityVerifier,
      PluginArtifactVerifier artifactVerifier,
      PluginOperationAuditRecorder auditRecorder,
      PluginRuntimeCoordinator runtimeCoordinator
  ) throws MalformedURLException, URISyntaxException {
    this.pluginClassloader =
        new PluginClassloader(
            parentClassloader,
            pluginStorage,
            compatibilityVerifier,
            artifactVerifier,
            auditRecorder,
            runtimeCoordinator);
  }

  /**
   * Constructs a new AbstractPluginsManager instance with custom verification,
   * audit recording, runtime coordination, and task storage.
   *
   * @param parentClassloader     the parent class loader for the plugin class loader
   * @param pluginStorage         the storage for managing plugins
   * @param compatibilityVerifier plugin compatibility verifier
   * @param artifactVerifier      plugin artifact verifier
   * @param auditRecorder         plugin operation audit recorder
   * @param runtimeCoordinator    plugin runtime operation coordinator
   * @param taskStore             plugin runtime task store
   * @throws MalformedURLException if an invalid URL is encountered
   * @throws URISyntaxException    if an invalid URI is encountered
   */
  public AbstractPluginsManager(
      ClassLoader parentClassloader,
      Storage<Plugin> pluginStorage,
      PluginCompatibilityVerifier compatibilityVerifier,
      PluginArtifactVerifier artifactVerifier,
      PluginOperationAuditRecorder auditRecorder,
      PluginRuntimeCoordinator runtimeCoordinator,
      PluginTaskStore taskStore
  ) throws MalformedURLException, URISyntaxException {
    this.pluginClassloader =
        new PluginClassloader(
            parentClassloader,
            pluginStorage,
            compatibilityVerifier,
            artifactVerifier,
            auditRecorder,
            runtimeCoordinator,
            taskStore);
  }

  /**
   * Constructs a new AbstractPluginsManager instance with the default class loader
   * and the specified plugin storage.
   *
   * @param pluginStorage the storage for managing plugins
   * @throws MalformedURLException if an invalid URL is encountered
   * @throws URISyntaxException    if an invalid URI is encountered
   */
  public AbstractPluginsManager(Storage<Plugin> pluginStorage)
      throws MalformedURLException, URISyntaxException {
    this(ApplicationContextHolder.getClassloader(), pluginStorage);
  }

  /**
   * Constructs a new AbstractPluginsManager instance with custom compatibility verification.
   *
   * @param pluginStorage         the storage for managing plugins
   * @param compatibilityVerifier plugin compatibility verifier
   * @throws MalformedURLException if an invalid URL is encountered
   * @throws URISyntaxException    if an invalid URI is encountered
   */
  public AbstractPluginsManager(Storage<Plugin> pluginStorage, PluginCompatibilityVerifier compatibilityVerifier)
      throws MalformedURLException, URISyntaxException {
    this(ApplicationContextHolder.getClassloader(), pluginStorage, compatibilityVerifier);
  }

  /**
   * Constructs a new AbstractPluginsManager instance with custom verification.
   *
   * @param pluginStorage         the storage for managing plugins
   * @param compatibilityVerifier plugin compatibility verifier
   * @param artifactVerifier      plugin artifact verifier
   * @throws MalformedURLException if an invalid URL is encountered
   * @throws URISyntaxException    if an invalid URI is encountered
   */
  public AbstractPluginsManager(
      Storage<Plugin> pluginStorage,
      PluginCompatibilityVerifier compatibilityVerifier,
      PluginArtifactVerifier artifactVerifier
  ) throws MalformedURLException, URISyntaxException {
    this(ApplicationContextHolder.getClassloader(), pluginStorage, compatibilityVerifier, artifactVerifier);
  }

  /**
   * Constructs a new AbstractPluginsManager instance with custom verification and audit recording.
   *
   * @param pluginStorage         the storage for managing plugins
   * @param compatibilityVerifier plugin compatibility verifier
   * @param artifactVerifier      plugin artifact verifier
   * @param auditRecorder         plugin operation audit recorder
   * @throws MalformedURLException if an invalid URL is encountered
   * @throws URISyntaxException    if an invalid URI is encountered
   */
  public AbstractPluginsManager(
      Storage<Plugin> pluginStorage,
      PluginCompatibilityVerifier compatibilityVerifier,
      PluginArtifactVerifier artifactVerifier,
      PluginOperationAuditRecorder auditRecorder
  ) throws MalformedURLException, URISyntaxException {
    this(
        ApplicationContextHolder.getClassloader(),
        pluginStorage,
        compatibilityVerifier,
        artifactVerifier,
        auditRecorder);
  }

  /**
   * Constructs a new AbstractPluginsManager instance with custom verification,
   * audit recording, and runtime coordination.
   *
   * @param pluginStorage         the storage for managing plugins
   * @param compatibilityVerifier plugin compatibility verifier
   * @param artifactVerifier      plugin artifact verifier
   * @param auditRecorder         plugin operation audit recorder
   * @param runtimeCoordinator    plugin runtime operation coordinator
   * @throws MalformedURLException if an invalid URL is encountered
   * @throws URISyntaxException    if an invalid URI is encountered
   */
  public AbstractPluginsManager(
      Storage<Plugin> pluginStorage,
      PluginCompatibilityVerifier compatibilityVerifier,
      PluginArtifactVerifier artifactVerifier,
      PluginOperationAuditRecorder auditRecorder,
      PluginRuntimeCoordinator runtimeCoordinator
  ) throws MalformedURLException, URISyntaxException {
    this(
        ApplicationContextHolder.getClassloader(),
        pluginStorage,
        compatibilityVerifier,
        artifactVerifier,
        auditRecorder,
        runtimeCoordinator);
  }

  /**
   * Constructs a new AbstractPluginsManager instance with custom verification,
   * audit recording, runtime coordination, and task storage.
   *
   * @param pluginStorage         the storage for managing plugins
   * @param compatibilityVerifier plugin compatibility verifier
   * @param artifactVerifier      plugin artifact verifier
   * @param auditRecorder         plugin operation audit recorder
   * @param runtimeCoordinator    plugin runtime operation coordinator
   * @param taskStore             plugin runtime task store
   * @throws MalformedURLException if an invalid URL is encountered
   * @throws URISyntaxException    if an invalid URI is encountered
   */
  public AbstractPluginsManager(
      Storage<Plugin> pluginStorage,
      PluginCompatibilityVerifier compatibilityVerifier,
      PluginArtifactVerifier artifactVerifier,
      PluginOperationAuditRecorder auditRecorder,
      PluginRuntimeCoordinator runtimeCoordinator,
      PluginTaskStore taskStore
  ) throws MalformedURLException, URISyntaxException {
    this(
        ApplicationContextHolder.getClassloader(),
        pluginStorage,
        compatibilityVerifier,
        artifactVerifier,
        auditRecorder,
        runtimeCoordinator,
        taskStore);
  }

  /**
   * Constructs a new AbstractPluginsManager instance with default settings.
   *
   * @throws MalformedURLException if an invalid URL is encountered
   * @throws URISyntaxException    if an invalid URI is encountered
   */
  public AbstractPluginsManager() throws MalformedURLException, URISyntaxException {
    this(new MapPluginsStorage());
  }

  /**
   * Installs all plugins from the specified directory.
   *
   * @param path the directory containing the plugins to be installed
   * @return a list of installed Plugin instances
   * @throws Exception if an error occurs during installation
   */
  @Override
  public List<Plugin> installAll(File path) throws Exception {
    return pluginClassloader.installAll(path);
  }

  /**
   * Plans installation of all plugins from the specified directory without mutating runtime state.
   *
   * @param path the directory containing plugin archives
   * @return plugin installation plan
   */
  @Override
  public PluginInstallPlan planInstallAll(File path) {
    return pluginClassloader.planInstallAll(path);
  }

  /**
   * Installs a plugin from the specified URI.
   *
   * @param uri the URI of the plugin to be installed
   * @return the installed Plugin instance
   * @throws Exception if an error occurs during installation
   */
  @Override
  public Plugin install(URI uri) throws Exception {
    return pluginClassloader.install(uri);
  }

  /**
   * Plans installation of a plugin archive without mutating runtime state.
   *
   * @param uri the URI of the plugin archive to plan
   * @return plugin installation plan
   */
  @Override
  public PluginInstallPlan planInstall(URI uri) {
    return pluginClassloader.planInstall(uri);
  }

  /**
   * Upgrades an installed plugin from the specified URI.
   *
   * @param uri the URI of the new plugin archive
   * @return the upgraded Plugin instance
   * @throws Exception if the plugin is not installed or the upgrade fails
   */
  @Override
  public Plugin upgrade(URI uri) throws Exception {
    return pluginClassloader.upgrade(uri);
  }

  /**
   * Plans upgrade of an installed plugin without mutating runtime state.
   *
   * @param uri the URI of the new plugin archive to plan
   * @return plugin upgrade plan
   */
  @Override
  public PluginUpgradePlan planUpgrade(URI uri) {
    return pluginClassloader.planUpgrade(uri);
  }

  /**
   * Enables a disabled plugin without reinstalling the archive.
   *
   * @param pluginId the manifest id of the plugin to enable
   * @return the enabled plugin
   */
  @Override
  public Plugin enable(String pluginId) {
    return pluginClassloader.enable(pluginId);
  }

  /**
   * Disables an enabled plugin without removing its archive or class loader.
   *
   * @param pluginId the manifest id of the plugin to disable
   * @return the disabled plugin
   */
  @Override
  public Plugin disable(String pluginId) {
    return pluginClassloader.disable(pluginId);
  }

  /**
   * Reads and validates a plugin manifest without installing the plugin.
   *
   * @param uri the URI of the plugin archive to inspect
   * @return the validated plugin manifest
   * @throws Exception if the archive cannot be read or the manifest is invalid
   */
  @Override
  public PluginManifest inspect(URI uri) throws Exception {
    return pluginClassloader.inspect(uri);
  }

  /**
   * Returns a read model of the plugin registry.
   *
   * @return plugin registry view
   */
  @Override
  public PluginRegistryView registry() {
    return pluginClassloader.registry();
  }

  /**
   * Returns plugin operation audit entries.
   *
   * @return plugin operation audit entries
   */
  @Override
  public List<PluginOperationAudit> operationAudits() {
    return pluginClassloader.operationAudits();
  }

  /**
   * Returns plugin runtime task snapshots.
   *
   * @return plugin runtime task snapshots
   */
  @Override
  public List<PluginTaskSnapshot> operationTasks() {
    return pluginClassloader.operationTasks();
  }

  /**
   * Executes tasks from the queue. If a task fails, it is retried up to 10 times.
   * If the maximum retry limit is reached, an exception is thrown.
   */
  public void submit() {
    pluginClassloader.submit();
  }

  /**
   * Uninstalls a plugin by its manifest id.
   *
   * @param pluginId the manifest id of the plugin to uninstall
   */
  @Override
  public void uninstall(String pluginId) {
    pluginClassloader.uninstall(pluginId);
  }

  /**
   * Registers a new PluginInstanceHandler for managing plugin instances.
   *
   * @param handle the PluginInstanceHandler to register
   */
  @Override
  public void registerHandle(PluginInstanceHandler handle) {
    pluginClassloader.registerHandle(handle);
  }

  @Override
  public void registerInstallValidator(PluginInstallValidator validator) {
    pluginClassloader.registerInstallValidator(validator);
  }

  @Override
  public void registerInstallBatchValidator(PluginInstallBatchValidator validator) {
    pluginClassloader.registerInstallBatchValidator(validator);
  }

  @Override
  public void unregisterInstallValidator(PluginInstallValidator validator) {
    pluginClassloader.unregisterInstallValidator(validator);
  }

  @Override
  public void unregisterInstallBatchValidator(PluginInstallBatchValidator validator) {
    pluginClassloader.unregisterInstallBatchValidator(validator);
  }

  @Override
  public void registerLifecycleHandler(PluginLifecycleHandler handler) {
    pluginClassloader.registerLifecycleHandler(handler);
  }

  @Override
  public void unregisterLifecycleHandler(PluginLifecycleHandler handler) {
    pluginClassloader.unregisterLifecycleHandler(handler);
  }

  /**
   * Retrieves the storage used for managing plugins.
   *
   * @return the Storage instance containing plugin data
   */
  @Override
  public Storage<Plugin> getStorage() {
    return pluginClassloader.getStorage();
  }
}
