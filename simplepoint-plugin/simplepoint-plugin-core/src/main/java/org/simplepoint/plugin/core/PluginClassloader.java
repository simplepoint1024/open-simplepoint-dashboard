/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.core;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.NotDirectoryException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.api.data.Storage;
import org.simplepoint.core.ApplicationClassLoader;
import org.simplepoint.plugin.api.Plugin;
import org.simplepoint.plugin.api.PluginInstanceHandler;
import org.simplepoint.plugin.api.exception.ClassExistException;
import org.simplepoint.plugin.api.exception.PluginExistException;

/**
 * A custom class loader for managing plugins.
 * This class extends ApplicationClassLoader to support plugin installation,
 * uninstallation, and lifecycle management of plugin instances.
 */
@Getter
@Slf4j
public final class PluginClassloader extends ApplicationClassLoader {

  private final Storage<Plugin> storage;

  private final Queue<Runnable> handleQueue = new LinkedBlockingQueue<>();

  /**
   * Constructs a new PluginClassloader instance.
   *
   * @param parent  the parent class loader
   * @param storage the plugin storage used for managing plugin data
   * @param urls    the array of URLs for loading plugin resources
   * @throws MalformedURLException if an invalid URL is encountered
   * @throws URISyntaxException    if an invalid URI is encountered
   */
  public PluginClassloader(ClassLoader parent, Storage<Plugin> storage, String... urls)
      throws MalformedURLException, URISyntaxException {
    super(toUrl(urls), parent);
    this.storage = storage;
  }

  /**
   * Converts an array of string paths to an array of URL objects.
   *
   * @param url the array of string paths
   * @return the array of URL objects
   * @throws URISyntaxException    if an invalid URI is encountered
   * @throws MalformedURLException if an invalid URL is encountered
   */
  private static URL[] toUrl(String[] url) throws URISyntaxException, MalformedURLException {
    List<URL> urls = new ArrayList<>(url.length);
    for (String s : url) {
      urls.add(new URI(s).toURL());
    }
    return urls.toArray(new URL[0]);
  }

  /**
   * Registers a plugin instance handler for processing plugins.
   *
   * @param processes the PluginInstanceHandler to register
   */
  public void registerHandle(PluginInstanceHandler processes) {
    PluginContextHolder.register(processes);
  }

  /**
   * Installs all plugins from the specified directory.
   *
   * @param path the directory containing the plugins to be installed
   * @return a list of installed Plugin instances
   * @throws Exception if an error occurs during installation
   */
  public List<Plugin> installAll(File path) throws Exception {
    String pathName = path.getPath();
    if (!path.exists()) {
      boolean ignore = path.mkdirs();
    }
    if (!path.isDirectory()) {
      throw new NotDirectoryException(pathName);
    }
    List<Plugin> jars = new ArrayList<>();
    String dirPath = pathName.endsWith(File.separator) ? pathName : pathName + File.separator;
    for (String jarName : Objects.requireNonNull(path.list((ignore, s) -> s.endsWith(".jar")))) {
      jars.add(install(new File(dirPath + jarName).toURI()));
    }
    return jars;
  }

  /**
   * Installs a single plugin from the specified URI.
   *
   * @param uri the URI of the plugin to be installed
   * @return the installed Plugin instance
   * @throws Exception if an error occurs during installation
   */
  public synchronized Plugin install(URI uri) throws Exception {
    long timeMillis = System.currentTimeMillis();
    log.info("正在加载插件：{}", uri);
    Plugin plugin = this.analyzeJar(uri);
    log.info("插件加载成功!总共耗时:{}ms", System.currentTimeMillis() - timeMillis);
    return storage.save(plugin);
  }

  /**
   * Executes tasks from the queue. If a task fails, it is retried up to 10 times.
   * If the maximum retry limit is reached, an exception is thrown.
   */
  public synchronized void submit() {
    Map<Runnable, Integer> traversalCount = new HashMap<>();
    while (!this.handleQueue.isEmpty()) {
      Runnable poll = this.handleQueue.poll();
      try {
        // Execute the task
        poll.run();
        traversalCount.remove(poll);
      } catch (Exception e) {
        // Record the number of failures
        traversalCount.put(poll, traversalCount.getOrDefault(poll, 0) + 1);
        if (traversalCount.getOrDefault(poll, 0) >= 10) {
          // If the task fails 10 times, throw an exception
          throw e;
        }
        // Re-add the task to the queue for retry
        this.handleQueue.add(poll);
      }
    }
  }

  /**
   * Uninstalls a plugin by its package name.
   *
   * @param packageName the package name of the plugin to uninstall
   */
  public synchronized void uninstall(String packageName) {
    Plugin plugin = storage.find(packageName);
    if (plugin != null) {
      log.debug("正在卸载插件");
      printPluginInfo(plugin.metadata());
      uninstall(plugin.registered());
      storage.remove(plugin.metadata().getPackageName());
      log.debug("清理完成！卸载成功！");
    }
  }

  /**
   * Uninstalls the registered classes of a plugin.
   *
   * @param classes the registered plugin instances to uninstall
   */
  private synchronized void uninstall(Map<String, Set<Plugin.PluginInstance>> classes) {
    classes.forEach((group, beanContexts) -> beanContexts.forEach(beanContext -> {
      try {
        getHandlers().get(group).rollback(beanContext);
        beanContext.clearInstance();
        log.info("正在注销实例：{}", beanContext.getName());
        log.info("正在销毁实例：{}", beanContext.getBeanClassName());
      } catch (Exception e) {
        log.warn(e.getMessage());
      }
    }));
    log.debug("实例已全部销毁！正在清理插件！");
    System.gc();
  }

  /**
   * Analyzes and processes the plugin JAR file.
   *
   * @param uri the URI of the plugin to analyze
   * @return the analyzed Plugin instance
   * @throws Exception if an error occurs during analysis
   */
  private synchronized Plugin analyzeJar(URI uri) throws Exception {
    Map<String, Set<Plugin.PluginInstance>> classes = new HashMap<>();
    try (JarFile jarFile = new JarFile(new File(uri))) {
      JarEntry jarEntry = jarFile.getJarEntry("META-INF/plugin-config.xml");
      log.debug("正在加载插件信息！");

      File configFile = File.createTempFile("plugin-config", ".xml");
      try (
          var fileWriter = new FileWriter(configFile);
          var out = new BufferedWriter(fileWriter);
          var in = jarFile.getInputStream(jarEntry);
          var inputStreamReader = new InputStreamReader(in, StandardCharsets.UTF_8)
      ) {
        inputStreamReader.transferTo(out);
      }

      Plugin.PluginMetadata pluginMetadata =
          ConfigReader.Companion.read(configFile, Plugin.PluginMetadata.class);
      log.debug("读取成功,信息如下");
      printPluginInfo(pluginMetadata);
      checkPlugin(new Plugin(uri, pluginMetadata, classes));
      super.addURL(uri.toURL());
      merge(classes, registerInstances(pluginMetadata.getInstances()));
      merge(classes,
          registerInstances(analyzeBeanPackageScan(pluginMetadata.getPackageScan(), jarFile)));
      return new Plugin(uri, pluginMetadata, classes);
    } catch (ClassExistException classExistException) {
      log.error("安装失败，正在清理本次安装！");
      merge(classes, classExistException.getData());
      uninstall(classes);
      log.info("清理完成！");
      throw classExistException;
    }
  }

  /**
   * Checks whether the plugin already exists in storage.
   *
   * @param plugin the plugin to check
   * @throws PluginExistException if the plugin already exists
   */
  private void checkPlugin(Plugin plugin) throws PluginExistException {
    Plugin pluginInfo = storage.find(plugin.metadata().getPackageName());
    if (pluginInfo != null) {
      log.error("该插件已经加载完毕，已跳过加载！");
      printPluginInfo(pluginInfo.metadata());
      throw new PluginExistException("该插件已存在，终止并清理本次安装！", plugin);
    }
  }

  /**
   * Merges multiple maps of plugin instances into a single map.
   *
   * @param map  the target map to merge into
   * @param maps the maps to be merged
   */
  @SafeVarargs
  private void merge(Map<String, Set<Plugin.PluginInstance>> map,
                     Map<String, Set<Plugin.PluginInstance>>... maps) {
    for (Map<String, Set<Plugin.PluginInstance>> data : maps) {
      data.forEach((group, classes) -> {
        if (!map.containsKey(group)) {
          map.put(group, new HashSet<>());
        }
        map.get(group).addAll(classes);
      });
    }
  }

  /**
   * Registers beans based on the plugin instances.
   *
   * @param beansRegister the map of plugin instances to register
   * @return the map of registered plugin instances
   * @throws Exception if an error occurs during registration
   */
  private Map<String, Set<Plugin.PluginInstance>> registerInstances(
      Map<String, Set<Plugin.PluginInstance>> beansRegister) throws Exception {
    Map<String, Set<Plugin.PluginInstance>> beanContexts = new Hashtable<>();
    AtomicReference<Exception> exception = new AtomicReference<>(null);

    beansRegister.forEach((group, beans) -> beans.forEach(pluginInstance -> {
      if (getHandlers().containsKey(group)) {
        if (!beanContexts.containsKey(group)) {
          beanContexts.put(group, new HashSet<>());
        }
        PluginInstanceHandler handler = getHandlers().get(group);
        if (handler != null) {
          try {
            String name = pluginInstance.getName();
            log.info("正在初始化实例：[分组：{} 名称:{}]", group, name);
            pluginInstance.classes(this.loadClass(name));
            handleQueue.add(() -> {
              log.info("正在注册实例：[分组：{} 名称:{}]", group, name);
              handler.handle(pluginInstance);
              log.info("注册实例成功：[分组：{} 名称:{}]", group, name);
            });
            beanContexts.get(group).add(pluginInstance);
            log.info("初始化实例完成：[分组：{} 名称:{}]", group, name);
          } catch (Exception e) {
            handler.rollback(pluginInstance);
            exception.set(e);
          }
        }
      }
    }));
    if (exception.get() != null) {
      throw exception.get();
    }
    return beanContexts;
  }

  /**
   * Parses and scans the specified package for beans to register.
   *
   * @param packageScan a map containing package group names and their corresponding package paths
   * @param jarFile     the JAR file to scan for classes
   * @return a map containing plugin instances grouped by their specified group names
   */
  private Map<String, Set<Plugin.PluginInstance>> analyzeBeanPackageScan(
      Map<String, String> packageScan, JarFile jarFile) {
    log.debug("正在扫描实例包!");
    Map<String, Set<Plugin.PluginInstance>> beansRegister = new HashMap<>();

    // Iterate over all registered handlers and perform package scanning
    getHandlers().forEach((group, beans) -> {
      if (packageScan.containsKey(group)) {
        // Initialize the group in beansRegister if not already present
        beansRegister.computeIfAbsent(group, k -> new HashSet<>());

        String s = packageScan.get(group);
        List<String> packages = s.contains(",") ? Arrays.asList(s.split(",")) : List.of(s);

        // Iterate over all entries in the JAR file
        Iterator<JarEntry> jarEntryIterator = jarFile.entries().asIterator();
        while (jarEntryIterator.hasNext()) {
          JarEntry jarEntry = jarEntryIterator.next();
          String realName = jarEntry.getRealName().replaceAll("/", ".");

          // Check if the entry belongs to one of the specified packages
          if (packages.stream().anyMatch(packageName -> realName.startsWith(packageName)
              && (realName.endsWith(".class") || realName.endsWith(".kt")))) {
            String className = realName.substring(0, realName.lastIndexOf("."));
            beansRegister.get(group).add(new Plugin.PluginInstance(className, className, group));
            log.info("发现实例:{}", className);
          }
        }
      }
    });

    log.debug("包扫描完成！");
    return beansRegister;
  }

  /**
   * Retrieves all registered plugin instance handlers.
   *
   * @return a map containing plugin group names and their corresponding instance handlers
   */
  private Map<String, PluginInstanceHandler> getHandlers() {
    return PluginContextHolder.getHeaders();
  }

  /**
   * Logs detailed information about the given plugin metadata.
   *
   * @param metadata the plugin metadata containing details such as name, version, and author
   */
  public void printPluginInfo(Plugin.PluginMetadata metadata) {
    log.info("====================Plugin Info====================");
    log.info("插件名称：{}", metadata.getName());
    log.info("插件包名：{}", metadata.getPackageName());
    log.info("插件作者：{}", metadata.getAuthor());
    log.info("插件版本：{}", metadata.getVersion());
    log.info("====================Plugin Info====================");
  }

}
