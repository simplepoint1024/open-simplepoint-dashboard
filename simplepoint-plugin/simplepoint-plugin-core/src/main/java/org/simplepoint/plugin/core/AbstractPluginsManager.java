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
import org.simplepoint.plugin.api.PluginInstanceHandler;
import org.simplepoint.plugin.api.PluginsManager;

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
   * Executes tasks from the queue. If a task fails, it is retried up to 10 times.
   * If the maximum retry limit is reached, an exception is thrown.
   */
  public void submit() {
    pluginClassloader.submit();
  }

  /**
   * Uninstalls a plugin by its package name.
   *
   * @param packageName the package name of the plugin to uninstall
   */
  @Override
  public void uninstall(String packageName) {
    pluginClassloader.uninstall(packageName);
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
