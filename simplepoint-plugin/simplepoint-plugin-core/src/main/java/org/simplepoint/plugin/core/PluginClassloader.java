/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.core;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.NotDirectoryException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.api.data.Storage;
import org.simplepoint.core.ApplicationClassLoader;
import org.simplepoint.plugin.api.Plugin;
import org.simplepoint.plugin.api.PluginArtifact;
import org.simplepoint.plugin.api.PluginInstallBatchValidator;
import org.simplepoint.plugin.api.PluginInstallValidator;
import org.simplepoint.plugin.api.PluginInstanceHandler;
import org.simplepoint.plugin.api.PluginLifecycleHandler;
import org.simplepoint.plugin.api.PluginOperationAuditRecorder;
import org.simplepoint.plugin.api.PluginOperationCallback;
import org.simplepoint.plugin.api.PluginOperationContext;
import org.simplepoint.plugin.api.PluginOperationEvent;
import org.simplepoint.plugin.api.PluginRuntimeCoordinator;
import org.simplepoint.plugin.api.PluginStatus;
import org.simplepoint.plugin.api.PluginTaskStore;
import org.simplepoint.plugin.api.exception.ClassExistException;
import org.simplepoint.plugin.api.exception.PluginExistException;
import org.simplepoint.plugin.api.management.PluginInstallPlan;
import org.simplepoint.plugin.api.management.PluginInstallPlanIssue;
import org.simplepoint.plugin.api.management.PluginInstallPlanIssueCode;
import org.simplepoint.plugin.api.management.PluginOperation;
import org.simplepoint.plugin.api.management.PluginOperationAudit;
import org.simplepoint.plugin.api.management.PluginOperationOutcome;
import org.simplepoint.plugin.api.management.PluginRegistryView;
import org.simplepoint.plugin.api.management.PluginTaskSnapshot;
import org.simplepoint.plugin.api.management.PluginTaskStatus;
import org.simplepoint.plugin.api.management.PluginUpgradePlan;
import org.simplepoint.plugin.api.manifest.PluginManifest;

/**
 * A custom class loader for managing plugins.
 * This class extends ApplicationClassLoader to support plugin installation,
 * uninstallation, and lifecycle management of plugin instances.
 */
@Getter
@Slf4j
public final class PluginClassloader extends ApplicationClassLoader {

  private final Storage<Plugin> storage;

  private final Queue<PluginTask> handleQueue = new LinkedBlockingQueue<>();
  private final List<PluginInstallBatchValidator> installBatchValidators = new CopyOnWriteArrayList<>();
  private final List<PluginInstallValidator> installValidators = new CopyOnWriteArrayList<>();
  private final List<PluginLifecycleHandler> lifecycleHandlers = new CopyOnWriteArrayList<>();
  private final PluginDependencyResolver dependencyResolver = new PluginDependencyResolver();
  private final PluginInstallPlanFactory installPlanFactory = new PluginInstallPlanFactory();
  private final PluginUpgradePlanFactory upgradePlanFactory = new PluginUpgradePlanFactory();
  private final PluginRegistryViewFactory registryViewFactory = new PluginRegistryViewFactory();
  private final PluginBackendGroupVerifier backendGroupVerifier = new PluginBackendGroupVerifier();
  private final PluginInstanceRegistryValidator instanceRegistryValidator = new PluginInstanceRegistryValidator();
  private final PluginCompatibilityVerifier compatibilityVerifier;
  private final PluginArtifactVerifier artifactVerifier;
  private final PluginOperationAuditSupport auditSupport;
  private final PluginRuntimeCoordinator runtimeCoordinator;
  private final PluginArtifactHasher artifactHasher = new Sha256PluginArtifactHasher();
  // per-plugin isolated classloaders
  private final Map<String, URLClassLoader> pluginClassLoaders = new ConcurrentHashMap<>();
  // dependency graph: plugin id -> list of dependency plugin ids
  private final Map<String, List<String>> pluginDependencies = new ConcurrentHashMap<>();
  private final PluginTaskStore taskStore;

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
    this(parent, storage, PluginCompatibilityVerifier.defaults(), urls);
  }

  /**
   * Constructs a new PluginClassloader instance.
   *
   * @param parent                the parent class loader
   * @param storage               the plugin storage used for managing plugin data
   * @param compatibilityVerifier plugin compatibility verifier
   * @param urls                  the array of URLs for loading plugin resources
   * @throws MalformedURLException if an invalid URL is encountered
   * @throws URISyntaxException    if an invalid URI is encountered
   */
  public PluginClassloader(
      ClassLoader parent,
      Storage<Plugin> storage,
      PluginCompatibilityVerifier compatibilityVerifier,
      String... urls
  ) throws MalformedURLException, URISyntaxException {
    this(parent, storage, compatibilityVerifier, NoopPluginArtifactVerifier.INSTANCE, urls);
  }

  /**
   * Constructs a new PluginClassloader instance.
   *
   * @param parent                the parent class loader
   * @param storage               the plugin storage used for managing plugin data
   * @param compatibilityVerifier plugin compatibility verifier
   * @param artifactVerifier      plugin artifact verifier
   * @param urls                  the array of URLs for loading plugin resources
   * @throws MalformedURLException if an invalid URL is encountered
   * @throws URISyntaxException    if an invalid URI is encountered
   */
  public PluginClassloader(
      ClassLoader parent,
      Storage<Plugin> storage,
      PluginCompatibilityVerifier compatibilityVerifier,
      PluginArtifactVerifier artifactVerifier,
      String... urls
  ) throws MalformedURLException, URISyntaxException {
    this(
        parent,
        storage,
        compatibilityVerifier,
        artifactVerifier,
        new InMemoryPluginOperationAuditRecorder(),
        urls);
  }

  /**
   * Constructs a new PluginClassloader instance.
   *
   * @param parent                the parent class loader
   * @param storage               the plugin storage used for managing plugin data
   * @param compatibilityVerifier plugin compatibility verifier
   * @param artifactVerifier      plugin artifact verifier
   * @param auditRecorder         plugin operation audit recorder
   * @param urls                  the array of URLs for loading plugin resources
   * @throws MalformedURLException if an invalid URL is encountered
   * @throws URISyntaxException    if an invalid URI is encountered
   */
  public PluginClassloader(
      ClassLoader parent,
      Storage<Plugin> storage,
      PluginCompatibilityVerifier compatibilityVerifier,
      PluginArtifactVerifier artifactVerifier,
      PluginOperationAuditRecorder auditRecorder,
      String... urls
  ) throws MalformedURLException, URISyntaxException {
    this(
        parent,
        storage,
        compatibilityVerifier,
        artifactVerifier,
        auditRecorder,
        NoopPluginRuntimeCoordinator.INSTANCE,
        new InMemoryPluginTaskStore(),
        urls);
  }

  /**
   * Constructs a new PluginClassloader instance.
   *
   * @param parent                the parent class loader
   * @param storage               the plugin storage used to manage plugin data
   * @param compatibilityVerifier plugin compatibility verifier
   * @param artifactVerifier      plugin artifact verifier
   * @param auditRecorder         plugin operation audit recorder
   * @param runtimeCoordinator    plugin runtime operation coordinator
   * @param urls                  the array of URLs for loading plugin resources
   * @throws MalformedURLException if an invalid URL is encountered
   * @throws URISyntaxException    if an invalid URI is encountered
   */
  public PluginClassloader(
      ClassLoader parent,
      Storage<Plugin> storage,
      PluginCompatibilityVerifier compatibilityVerifier,
      PluginArtifactVerifier artifactVerifier,
      PluginOperationAuditRecorder auditRecorder,
      PluginRuntimeCoordinator runtimeCoordinator,
      String... urls
  ) throws MalformedURLException, URISyntaxException {
    this(
        parent,
        storage,
        compatibilityVerifier,
        artifactVerifier,
        auditRecorder,
        runtimeCoordinator,
        new InMemoryPluginTaskStore(),
        urls);
  }

  /**
   * Constructs a new PluginClassloader instance.
   *
   * @param parent                the parent class loader
   * @param storage               the plugin storage used to manage plugin data
   * @param compatibilityVerifier plugin compatibility verifier
   * @param artifactVerifier      plugin artifact verifier
   * @param auditRecorder         plugin operation audit recorder
   * @param runtimeCoordinator    plugin runtime operation coordinator
   * @param taskStore             plugin runtime task store
   * @param urls                  the array of URLs for loading plugin resources
   * @throws MalformedURLException if an invalid URL is encountered
   * @throws URISyntaxException    if an invalid URI is encountered
   */
  public PluginClassloader(
      ClassLoader parent,
      Storage<Plugin> storage,
      PluginCompatibilityVerifier compatibilityVerifier,
      PluginArtifactVerifier artifactVerifier,
      PluginOperationAuditRecorder auditRecorder,
      PluginRuntimeCoordinator runtimeCoordinator,
      PluginTaskStore taskStore,
      String... urls
  ) throws MalformedURLException, URISyntaxException {
    super(toUrl(urls), parent);
    this.storage = storage;
    this.compatibilityVerifier = Objects.requireNonNull(compatibilityVerifier, "compatibilityVerifier");
    this.artifactVerifier = Objects.requireNonNull(artifactVerifier, "artifactVerifier");
    this.auditSupport =
        new PluginOperationAuditSupport(Objects.requireNonNull(auditRecorder, "auditRecorder"));
    this.runtimeCoordinator = Objects.requireNonNull(runtimeCoordinator, "runtimeCoordinator");
    this.taskStore = Objects.requireNonNull(taskStore, "taskStore");
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
   * Registers an install-time validator.
   *
   * @param validator install validator
   */
  public void registerInstallValidator(PluginInstallValidator validator) {
    if (validator == null) {
      return;
    }
    installValidators.add(validator);
    installValidators.sort((left, right) -> Integer.compare(left.order(), right.order()));
  }

  /**
   * Registers an install-time batch validator.
   *
   * @param validator batch install validator
   */
  public void registerInstallBatchValidator(PluginInstallBatchValidator validator) {
    if (validator == null) {
      return;
    }
    installBatchValidators.add(validator);
    installBatchValidators.sort((left, right) -> Integer.compare(left.order(), right.order()));
  }

  /**
   * Unregisters an install-time validator.
   *
   * @param validator install validator
   */
  public void unregisterInstallValidator(PluginInstallValidator validator) {
    if (validator != null) {
      installValidators.remove(validator);
    }
  }

  /**
   * Unregisters an install-time batch validator.
   *
   * @param validator batch install validator
   */
  public void unregisterInstallBatchValidator(PluginInstallBatchValidator validator) {
    if (validator != null) {
      installBatchValidators.remove(validator);
    }
  }

  /**
   * Registers a plugin lifecycle handler.
   *
   * @param handler lifecycle handler
   */
  public void registerLifecycleHandler(PluginLifecycleHandler handler) {
    if (handler == null) {
      return;
    }
    lifecycleHandlers.add(handler);
    lifecycleHandlers.sort((left, right) -> Integer.compare(left.order(), right.order()));
  }

  /**
   * Unregisters a plugin lifecycle handler.
   *
   * @param handler lifecycle handler
   */
  public void unregisterLifecycleHandler(PluginLifecycleHandler handler) {
    if (handler != null) {
      lifecycleHandlers.remove(handler);
    }
  }

  /**
   * Installs all plugins from the specified directory.
   *
   * @param path the directory containing the plugins to be installed
   * @return a list of installed Plugin instances
   * @throws Exception if an error occurs during installation
   */
  public synchronized List<Plugin> installAll(File path) throws Exception {
    return coordinateOperation(PluginOperation.INSTALL, null, path.toURI(), () -> installAllInternal(path));
  }

  private List<Plugin> installAllInternal(File path) throws Exception {
    String pathName = path.getPath();
    if (!path.exists()) {
      boolean ignore = path.mkdirs();
    }
    if (!path.isDirectory()) {
      throw new NotDirectoryException(pathName);
    }
    File[] jarFiles = Objects.requireNonNull(path.listFiles((ignore, name) -> name.endsWith(".jar")));
    Arrays.sort(jarFiles, Comparator.comparing(File::getName));
    List<PluginDescriptor> descriptors = new ArrayList<>(jarFiles.length);
    for (File jarFile : jarFiles) {
      descriptors.add(readDescriptor(jarFile.toURI()));
    }
    List<PluginDescriptor> sorted = dependencyResolver.sort(descriptors, installedPluginVersions());
    validateBatchInstallCandidates(sorted);
    List<Plugin> installed = new ArrayList<>();
    try {
      for (PluginDescriptor descriptor : sorted) {
        installed.add(auditSupport.audit(
            PluginOperation.INSTALL,
            descriptor.uri(),
            descriptor.id(),
            () -> install(descriptor, false)));
      }
    } catch (Exception failure) {
      rollbackBatchInstall(installed, failure);
      throw failure;
    }
    return installed;
  }

  /**
   * Plans installation of all plugins from the specified directory without mutating runtime state.
   *
   * @param path the directory containing the plugins to be planned
   * @return plugin installation plan
   */
  public synchronized PluginInstallPlan planInstallAll(File path) {
    URI source = path.toURI();
    if (!path.exists()) {
      return installPlanFactory.empty(source);
    }
    if (!path.isDirectory()) {
      return installPlanFactory.failed(
          source,
          PluginInstallPlanIssueCode.DIRECTORY_INVALID,
          new NotDirectoryException(path.getPath()));
    }
    File[] jarFiles = Objects.requireNonNull(path.listFiles((ignore, name) -> name.endsWith(".jar")));
    Arrays.sort(jarFiles, Comparator.comparing(File::getName));
    List<PluginDescriptor> descriptors = new ArrayList<>(jarFiles.length);
    List<PluginInstallPlanIssue> issues = new ArrayList<>();
    for (File jarFile : jarFiles) {
      try {
        PluginDescriptor descriptor = readDescriptor(jarFile.toURI());
        descriptors.add(descriptor);
      } catch (Exception exception) {
        issues.add(installPlanFactory.issue(
            jarFile.toURI(),
            null,
            PluginInstallPlanIssueCode.DESCRIPTOR_INVALID,
            exception));
      }
    }
    validateBatchInstallCandidates(descriptors, source, issues);
    return installPlanFactory.create(source, descriptors, installedPluginVersions(), issues);
  }

  /**
   * Installs a single plugin from the specified URI.
   *
   * @param uri the URI of the plugin to be installed
   * @return the installed Plugin instance
   * @throws Exception if an error occurs during installation
   */
  public synchronized Plugin install(URI uri) throws Exception {
    return coordinateOperation(
        PluginOperation.INSTALL,
        null,
        uri,
        () -> auditSupport.audit(PluginOperation.INSTALL, uri, null, () -> install(readDescriptor(uri))));
  }

  private synchronized Plugin install(PluginDescriptor descriptor) throws Exception {
    return install(descriptor, true);
  }

  private synchronized Plugin install(PluginDescriptor descriptor, boolean validateBeforeInstall) throws Exception {
    final long timeMillis = System.currentTimeMillis();
    log.info("正在加载插件：{}", descriptor.uri());
    if (validateBeforeInstall) {
      dependencyResolver.verifyInstalledDependencies(descriptor, installedPluginVersions());
      validateSingleInstallCandidate(descriptor);
    }
    Plugin plugin = this.analyzeJar(descriptor);
    log.info("插件加载成功!总共耗时:{}ms", System.currentTimeMillis() - timeMillis);
    Plugin saved = storage.save(plugin.withStatus(PluginStatus.INSTALLED));
    enqueueTask(saved.manifest().getId(), PluginOperation.SUBMIT, () -> {
      publishInstalled(saved);
      markEnabled(saved.manifest().getId());
    });
    return saved;
  }

  /**
   * Plans installation of a plugin archive without mutating runtime state.
   *
   * @param uri the URI of the plugin archive to plan
   * @return plugin installation plan
   */
  public synchronized PluginInstallPlan planInstall(URI uri) {
    List<PluginInstallPlanIssue> issues = new ArrayList<>();
    try {
      PluginDescriptor descriptor = readDescriptor(uri);
      validateSingleInstallCandidate(descriptor, issues);
      return installPlanFactory.create(
          uri,
          List.of(descriptor),
          installedPluginVersions(),
          issues);
    } catch (Exception exception) {
      return installPlanFactory.failed(uri, PluginInstallPlanIssueCode.DESCRIPTOR_INVALID, exception);
    }
  }

  /**
   * Upgrades an installed plugin from the specified URI.
   *
   * @param uri the URI of the new plugin archive
   * @return the upgraded Plugin instance
   * @throws Exception if the plugin is not installed or the upgrade fails
   */
  public synchronized Plugin upgrade(URI uri) throws Exception {
    return coordinateOperation(
        PluginOperation.UPGRADE,
        null,
        uri,
        () -> auditSupport.audit(PluginOperation.UPGRADE, uri, null, () -> {
          PluginDescriptor descriptor = readDescriptor(uri);
          Plugin current = validateUpgradeCandidate(descriptor);
          String pluginId = descriptor.id();
          URI rollbackUri = current.path();
          uninstall(pluginId, false);
          try {
            Plugin upgraded = install(descriptor, false);
            submitQueuedTasks();
            Plugin enabled = storage.find(pluginId);
            return enabled == null ? upgraded : enabled;
          } catch (Exception upgradeFailure) {
            restorePreviousPlugin(rollbackUri, upgradeFailure);
            throw upgradeFailure;
          }
        }));
  }

  /**
   * Plans upgrade of an installed plugin without mutating runtime state.
   *
   * @param uri the URI of the new plugin archive to plan
   * @return plugin upgrade plan
   */
  public synchronized PluginUpgradePlan planUpgrade(URI uri) {
    List<PluginInstallPlanIssue> issues = new ArrayList<>();
    try {
      PluginDescriptor descriptor = readDescriptor(uri);
      String pluginId = descriptor.id();
      Plugin current = storage.find(pluginId);
      List<String> blockingDependents = dependentPluginIds(pluginId);
      if (current == null) {
        issues.add(upgradePlanFactory.issue(
            descriptor.uri(),
            pluginId,
            PluginInstallPlanIssueCode.UPGRADE_VALIDATION_FAILED,
            new IllegalStateException("Plugin " + pluginId + " is not installed")));
      }
      if (!blockingDependents.isEmpty()) {
        issues.add(upgradePlanFactory.issue(
            descriptor.uri(),
            pluginId,
            PluginInstallPlanIssueCode.UPGRADE_VALIDATION_FAILED,
            new IllegalStateException(
                "插件 " + pluginId + " 被以下插件依赖，无法升级: " + String.join(", ", blockingDependents))));
      }
      try {
        validateUpgradeArtifact(descriptor);
      } catch (Exception exception) {
        issues.add(upgradePlanFactory.issue(
            descriptor.uri(),
            pluginId,
            PluginInstallPlanIssueCode.UPGRADE_VALIDATION_FAILED,
            exception));
      }
      return upgradePlanFactory.create(
          uri,
          current,
          descriptor,
          installedPluginVersions(),
          blockingDependents,
          issues);
    } catch (Exception exception) {
      return upgradePlanFactory.failed(uri, PluginInstallPlanIssueCode.DESCRIPTOR_INVALID, exception);
    }
  }

  /**
   * Enables a disabled plugin without reinstalling its artifact.
   *
   * @param pluginId the manifest id of the plugin to enable
   * @return the enabled plugin
   */
  public synchronized Plugin enable(String pluginId) {
    return coordinateRuntimeOperation(
        PluginOperation.ENABLE,
        pluginId,
        null,
        () -> auditSupport.auditRuntime(
            PluginOperation.ENABLE,
            pluginId,
            () -> storage.find(pluginId),
            () -> enablePlugin(pluginId)));
  }

  /**
   * Disables an enabled plugin without removing its artifact or class loader.
   *
   * @param pluginId the manifest id of the plugin to disable
   * @return the disabled plugin
   */
  public synchronized Plugin disable(String pluginId) {
    return coordinateRuntimeOperation(
        PluginOperation.DISABLE,
        pluginId,
        null,
        () -> auditSupport.auditRuntime(
            PluginOperation.DISABLE,
            pluginId,
            () -> storage.find(pluginId),
            () -> disablePlugin(pluginId)));
  }

  private Plugin enablePlugin(String pluginId) {
    Plugin plugin = requirePlugin(pluginId);
    if (plugin.status() == PluginStatus.ENABLED) {
      return plugin;
    }
    if (plugin.status() != PluginStatus.DISABLED) {
      throw new IllegalStateException("Only disabled plugins can be enabled: " + pluginId);
    }
    assertRequiredDependenciesEnabled(plugin);
    Map<String, Set<Plugin.PluginInstance>> activated = new HashMap<>();
    Plugin enabling = storage.save(plugin.withStatus(PluginStatus.INSTALLED));
    try {
      activateInstances(enabling, activated);
      publishInstalled(enabling);
      return storage.save(enabling.withStatus(PluginStatus.ENABLED));
    } catch (RuntimeException e) {
      rollbackInstances(activated, false);
      storage.save(enabling.withFailure(e.getMessage()));
      throw e;
    }
  }

  private Plugin disablePlugin(String pluginId) {
    Plugin plugin = requirePlugin(pluginId);
    if (plugin.status() == PluginStatus.DISABLED) {
      return plugin;
    }
    if (plugin.status() != PluginStatus.ENABLED) {
      throw new IllegalStateException("Only enabled plugins can be disabled: " + pluginId);
    }
    assertNoActiveDependents(pluginId);
    Plugin disabled = storage.save(plugin.withStatus(PluginStatus.DISABLED));
    publishUninstalling(disabled);
    rollbackInstances(disabled.registered(), false);
    removeQueuedTasks(pluginId);
    return storage.find(pluginId);
  }

  /**
   * Reads and validates a plugin manifest without installing the plugin archive.
   *
   * @param uri the URI of the plugin archive to inspect
   * @return the validated plugin manifest
   * @throws IOException if the plugin archive cannot be read
   */
  public synchronized PluginManifest inspect(URI uri) throws IOException {
    return readDescriptor(uri).manifest();
  }

  /**
   * Returns a read model of the plugin registry.
   *
   * @return plugin registry view
   */
  public synchronized PluginRegistryView registry() {
    return registryViewFactory.create(storage.list(), Map.copyOf(pluginDependencies));
  }

  /**
   * Returns plugin operation audit entries.
   *
   * @return plugin operation audit entries
   */
  public synchronized List<PluginOperationAudit> operationAudits() {
    return auditSupport.list();
  }

  /**
   * Returns runtime task snapshots.
   *
   * @return runtime task snapshots
   */
  public synchronized List<PluginTaskSnapshot> operationTasks() {
    return taskStore.list().stream()
        .sorted(Comparator.comparing(PluginTaskSnapshot::createdAt))
        .toList();
  }

  private <T> T coordinateOperation(
      PluginOperation operation,
      String pluginId,
      URI source,
      PluginOperationCallback<T> callback
  ) throws Exception {
    PluginOperationContext context = new PluginOperationContext(
        operation,
        pluginId == null ? List.of() : List.of(pluginId),
        source,
        UUID.randomUUID().toString(),
        Instant.now());
    try {
      T result = runtimeCoordinator.coordinate(context, callback);
      publishOperationEvent(context, PluginOperationOutcome.SUCCESS, resolvePluginIds(context, result), null);
      return result;
    } catch (Exception exception) {
      publishOperationEvent(context, PluginOperationOutcome.FAILURE, context.pluginIds(), exception.getMessage());
      throw exception;
    }
  }

  private <T> T coordinateRuntimeOperation(
      PluginOperation operation,
      String pluginId,
      URI source,
      PluginOperationCallback<T> callback
  ) {
    try {
      return coordinateOperation(operation, pluginId, source, callback);
    } catch (RuntimeException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private void publishOperationEvent(
      PluginOperationContext context,
      PluginOperationOutcome outcome,
      List<String> pluginIds,
      String message
  ) {
    try {
      runtimeCoordinator.publish(new PluginOperationEvent(
          context.operation(),
          outcome,
          pluginIds,
          context.source(),
          context.operationId(),
          Instant.now(),
          message));
    } catch (RuntimeException exception) {
      log.warn("插件操作事件发布失败: {}", exception.getMessage());
    }
  }

  private List<String> resolvePluginIds(PluginOperationContext context, Object result) {
    if (result instanceof Plugin plugin) {
      return List.of(plugin.manifest().getId());
    }
    if (result instanceof List<?> items) {
      List<String> pluginIds = items.stream()
          .filter(Plugin.class::isInstance)
          .map(Plugin.class::cast)
          .map(plugin -> plugin.manifest().getId())
          .toList();
      if (!pluginIds.isEmpty()) {
        return pluginIds;
      }
    }
    return context.pluginIds();
  }

  private PluginDescriptor readDescriptor(URI uri) throws IOException {
    PluginArtifact artifact = artifactHasher.hash(uri);
    try (JarFile jarFile = new JarFile(new File(uri))) {
      PluginManifest manifest = PluginManifestReader.readRequired(jarFile);
      compatibilityVerifier.verify(manifest);
      artifactVerifier.verify(artifact, manifest);
      return PluginDescriptor.from(artifact, manifest);
    }
  }

  private Map<String, String> installedPluginVersions() {
    Map<String, String> versions = new LinkedHashMap<>();
    for (Plugin plugin : storage.list()) {
      versions.put(plugin.manifest().getId(), plugin.manifest().getVersion());
    }
    return versions;
  }

  private Map<String, String> installedPluginVersionsExcluding(String excludedPluginId) {
    Map<String, String> versions = installedPluginVersions();
    versions.remove(excludedPluginId);
    return versions;
  }

  /**
   * Executes tasks from the queue. If a task fails, it is retried up to 10 times.
   * If the maximum retry limit is reached, an exception is thrown.
   */
  public synchronized void submit() {
    coordinateRuntimeOperation(PluginOperation.SUBMIT, null, null, () -> {
      submitQueuedTasks();
      return null;
    });
  }

  private void submitQueuedTasks() {
    while (!this.handleQueue.isEmpty()) {
      PluginTask poll = this.handleQueue.poll();
      Instant startedAt = Instant.now();
      long startedNanos = System.nanoTime();
      while (true) {
        try {
          poll.running();
          saveTask(poll);
          poll.execute();
          poll.succeeded();
          saveTask(poll);
          auditSupport.record(
              PluginOperation.SUBMIT,
              PluginOperationOutcome.SUCCESS,
              storage.find(poll.pluginId()),
              null,
              poll.pluginId(),
              startedAt,
              startedNanos,
              null);
          break;
        } catch (Exception e) {
          poll.retrying(e);
          saveTask(poll);
          if (poll.attempts() >= 10) {
            poll.failed(e);
            saveTask(poll);
            markFailed(poll.pluginId(), e);
            Plugin failed = storage.find(poll.pluginId());
            auditSupport.record(
                PluginOperation.SUBMIT,
                PluginOperationOutcome.FAILURE,
                failed,
                null,
                poll.pluginId(),
                startedAt,
                startedNanos,
                e);
            rollbackFailedInstall(poll.pluginId(), e);
            throw e;
          }
        }
      }
    }
  }

  /**
   * Uninstalls a plugin by its manifest id.
   *
   * @param pluginId the manifest id of the plugin to uninstall
   */
  public synchronized void uninstall(String pluginId) {
    coordinateRuntimeOperation(
        PluginOperation.UNINSTALL,
        pluginId,
        null,
        () -> auditSupport.auditRuntime(
            PluginOperation.UNINSTALL,
            pluginId,
            () -> storage.find(pluginId),
            () -> {
              uninstall(pluginId, true);
              return null;
            }));
  }

  private void uninstall(String pluginId, boolean checkDependents) {
    // Prevent uninstall if other plugins depend on this one
    if (checkDependents) {
      assertNoDependents(pluginId);
    }

    Plugin plugin = storage.find(pluginId);
    if (plugin != null) {
      log.debug("正在卸载插件");
      printPluginInfo(plugin.manifest());
      storage.save(plugin.withStatus(PluginStatus.DISABLED));
      publishUninstalling(plugin);
      rollbackInstances(plugin.registered(), true);
      removeQueuedTasks(pluginId);
      storage.remove(plugin.manifest().getId());
      closeClassLoader(pluginId);
      pluginDependencies.remove(pluginId);
      log.debug("清理完成！卸载成功！");
    }
  }

  /**
   * Uninstalls the registered classes of a plugin.
   *
   * @param classes the registered plugin instances to uninstall
   */
  private synchronized void rollbackInstances(Map<String, Set<Plugin.PluginInstance>> classes, boolean clearLoadedClass) {
    classes.forEach((group, beanContexts) -> beanContexts.forEach(beanContext -> {
      try {
        getHandlers().get(group).rollback(beanContext);
        if (clearLoadedClass) {
          beanContext.clearInstance();
        } else {
          beanContext.clearRuntimeInstance();
        }
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
  private synchronized Plugin analyzeJar(PluginDescriptor descriptor) throws Exception {
    Map<String, Set<Plugin.PluginInstance>> classes = new HashMap<>();
    String pluginId = descriptor.id();
    try (JarFile jarFile = new JarFile(new File(descriptor.uri()))) {
      log.debug("正在加载插件信息！");
      PluginManifest manifest = descriptor.manifest();
      log.debug("读取成功,信息如下");
      printPluginInfo(manifest);
      checkPlugin(new Plugin(descriptor.artifact(), manifest, classes));
      backendGroupVerifier.verify(manifest, getHandlers().keySet());

      // Resolve dependencies classloaders
      String pkg = PluginManifestRuntime.pluginId(manifest);
      List<String> deps = resolveDependencyClassLoaders(descriptor);
      List<ClassLoader> depCls = new ArrayList<>();
      for (String dependencyId : deps) {
        depCls.add(pluginClassLoaders.get(dependencyId));
      }

      // Create dependency-aware classloader for this plugin
      URL[] urls = new URL[] { descriptor.uri().toURL() };
      URLClassLoader pluginCl = new DependencyAwareUrlClassLoader(urls, this.getParent(), depCls);
      pluginClassLoaders.put(pkg, pluginCl);
      pluginDependencies.put(pkg, List.copyOf(deps));

      Map<String, Set<Plugin.PluginInstance>> candidates = new HashMap<>();
      merge(candidates, PluginManifestRuntime.instances(manifest));
      merge(candidates, analyzeBeanPackageScan(PluginManifestRuntime.packageScan(manifest), jarFile));
      instanceRegistryValidator.validate(pluginId, candidates);

      // Use the plugin-specific classloader to register instances
      merge(classes, registerInstances(pkg, candidates, pluginCl));
      return new Plugin(descriptor.artifact(), manifest, classes);
    } catch (ClassExistException classExistException) {
      log.error("安装失败，正在清理本次安装！");
      merge(classes, classExistException.getData());
      rollbackInstances(classes, true);
      removeQueuedTasks(pluginId);
      closeClassLoader(pluginId);
      pluginDependencies.remove(pluginId);
      log.info("清理完成！");
      throw classExistException;
    } catch (Exception exception) {
      removeQueuedTasks(pluginId);
      closeClassLoader(pluginId);
      pluginDependencies.remove(pluginId);
      throw exception;
    }
  }

  private void validateSingleInstallCandidate(PluginDescriptor descriptor, List<PluginInstallPlanIssue> issues) {
    try {
      validateSingleInstallCandidate(descriptor);
    } catch (Exception exception) {
      issues.add(installPlanFactory.issue(
          descriptor.uri(),
          descriptor.id(),
          PluginInstallPlanIssueCode.INSTALL_VALIDATION_FAILED,
          exception));
    }
  }

  private void validateSingleInstallCandidate(PluginDescriptor descriptor) throws IOException {
    validateInstallStructure(descriptor);
    validateInstallContributions(pluginCandidate(descriptor));
  }

  private void validateBatchInstallCandidates(List<PluginDescriptor> descriptors) throws IOException {
    for (PluginDescriptor descriptor : descriptors) {
      validateInstallStructure(descriptor);
    }
    validateInstallBatchContributions(pluginCandidates(descriptors));
  }

  private void validateBatchInstallCandidates(
      List<PluginDescriptor> descriptors,
      URI source,
      List<PluginInstallPlanIssue> issues
  ) {
    boolean structureValid = true;
    for (PluginDescriptor descriptor : descriptors) {
      try {
        validateInstallStructure(descriptor);
      } catch (Exception exception) {
        structureValid = false;
        issues.add(installPlanFactory.issue(
            descriptor.uri(),
            descriptor.id(),
            PluginInstallPlanIssueCode.INSTALL_VALIDATION_FAILED,
            exception));
      }
    }
    if (!structureValid) {
      return;
    }
    try {
      validateInstallBatchContributions(pluginCandidates(descriptors));
    } catch (Exception exception) {
      issues.add(installPlanFactory.issue(
          source,
          null,
          PluginInstallPlanIssueCode.INSTALL_VALIDATION_FAILED,
          exception));
    }
  }

  private void validateInstallStructure(PluginDescriptor descriptor) throws IOException {
    try (JarFile jarFile = new JarFile(new File(descriptor.uri()))) {
      PluginManifest manifest = descriptor.manifest();
      backendGroupVerifier.verify(manifest, getHandlers().keySet());
      Map<String, Set<Plugin.PluginInstance>> candidates = new HashMap<>();
      merge(candidates, PluginManifestRuntime.instances(manifest));
      merge(candidates, analyzeBeanPackageScan(PluginManifestRuntime.packageScan(manifest), jarFile));
      instanceRegistryValidator.validate(descriptor.id(), candidates);
    }
  }

  private void validateInstallContributions(Plugin plugin) {
    for (PluginInstallValidator validator : installValidators) {
      if (validator.supports(plugin)) {
        validator.validate(plugin);
      }
    }
  }

  private void validateInstallBatchContributions(List<Plugin> plugins) {
    Set<PluginInstallValidator> batchAwareValidators = Collections.newSetFromMap(new IdentityHashMap<>());
    for (PluginInstallBatchValidator validator : installBatchValidators) {
      if (validator instanceof PluginInstallValidator installValidator) {
        batchAwareValidators.add(installValidator);
      }
      if (validator.supports(plugins)) {
        validator.validate(plugins);
      }
    }
    for (Plugin plugin : plugins) {
      for (PluginInstallValidator validator : installValidators) {
        if (!batchAwareValidators.contains(validator) && validator.supports(plugin)) {
          validator.validate(plugin);
        }
      }
    }
  }

  private Plugin pluginCandidate(PluginDescriptor descriptor) {
    return new Plugin(descriptor.artifact(), descriptor.manifest(), Map.of());
  }

  private List<Plugin> pluginCandidates(List<PluginDescriptor> descriptors) {
    return descriptors.stream().map(this::pluginCandidate).toList();
  }

  private void publishInstalled(Plugin plugin) {
    for (PluginLifecycleHandler handler : lifecycleHandlers) {
      if (handler.supports(plugin)) {
        handler.installed(plugin);
      }
    }
  }

  private void publishUninstalling(Plugin plugin) {
    for (int i = lifecycleHandlers.size() - 1; i >= 0; i--) {
      PluginLifecycleHandler handler = lifecycleHandlers.get(i);
      if (handler.supports(plugin)) {
        handler.uninstalling(plugin);
      }
    }
  }

  private List<String> resolveDependencyClassLoaders(PluginDescriptor descriptor) {
    List<String> resolved = new ArrayList<>();
    for (String dependencyId : descriptor.requiredDependencies()) {
      if (!pluginClassLoaders.containsKey(dependencyId)) {
        throw new IllegalStateException("找不到依赖插件 '" + dependencyId + "' 的类加载器，请先安装依赖或调整加载顺序。");
      }
      resolved.add(dependencyId);
    }
    for (String dependencyId : descriptor.optionalDependencies()) {
      if (pluginClassLoaders.containsKey(dependencyId)) {
        resolved.add(dependencyId);
      }
    }
    return resolved;
  }

  private void restorePreviousPlugin(URI rollbackUri, Exception upgradeFailure) {
    try {
      auditSupport.audit(PluginOperation.INSTALL, rollbackUri, null, () -> install(readDescriptor(rollbackUri)));
      submitQueuedTasks();
    } catch (Exception restoreFailure) {
      upgradeFailure.addSuppressed(restoreFailure);
    }
  }

  private Plugin validateUpgradeCandidate(PluginDescriptor descriptor) throws IOException {
    String pluginId = descriptor.id();
    Plugin current = storage.find(pluginId);
    if (current == null) {
      throw new IllegalStateException("Plugin " + pluginId + " is not installed");
    }
    assertNoDependents(pluginId);
    validateUpgradeArtifact(descriptor);
    return current;
  }

  private void validateUpgradeArtifact(PluginDescriptor descriptor) throws IOException {
    dependencyResolver.verifyInstalledDependencies(
        descriptor,
        installedPluginVersionsExcluding(descriptor.id()));
    validateSingleInstallCandidate(descriptor);
  }

  private void assertNoDependents(String pluginId) {
    List<String> blockers = dependentPluginIds(pluginId);
    if (!blockers.isEmpty()) {
      throw new IllegalStateException("插件 " + pluginId + " 被以下插件依赖，无法卸载: " + String.join(", ", blockers));
    }
  }

  private List<String> dependentPluginIds(String pluginId) {
    List<String> dependents = new ArrayList<>();
    pluginDependencies.forEach((candidateId, dependencies) -> {
      if (dependencies != null && dependencies.contains(pluginId)) {
        dependents.add(candidateId);
      }
    });
    dependents.sort(String::compareTo);
    return dependents;
  }

  private void assertNoActiveDependents(String pluginId) {
    List<String> blockers = new ArrayList<>();
    pluginDependencies.forEach((pkg, deps) -> {
      Plugin dependent = storage.find(pkg);
      if (deps != null && deps.contains(pluginId) && dependent != null
          && dependent.status() != PluginStatus.DISABLED && dependent.status() != PluginStatus.FAILED) {
        blockers.add(pkg);
      }
    });
    if (!blockers.isEmpty()) {
      throw new IllegalStateException("插件 " + pluginId + " 被以下已启用插件依赖，无法禁用: " + String.join(", ", blockers));
    }
  }

  private void assertRequiredDependenciesEnabled(Plugin plugin) {
    String pluginId = plugin.manifest().getId();
    for (String dependencyId : PluginManifestRuntime.requiredDependencyIds(plugin.manifest())) {
      Plugin dependency = storage.find(dependencyId);
      if (dependency == null || dependency.status() != PluginStatus.ENABLED) {
        throw new IllegalStateException("插件 " + pluginId + " 的必需依赖 " + dependencyId + " 尚未启用");
      }
    }
  }

  private Plugin requirePlugin(String pluginId) {
    Plugin plugin = storage.find(pluginId);
    if (plugin == null) {
      throw new IllegalStateException("Plugin " + pluginId + " is not installed");
    }
    return plugin;
  }

  private void activateInstances(Plugin plugin, Map<String, Set<Plugin.PluginInstance>> activated) {
    String pluginId = plugin.manifest().getId();
    URLClassLoader classLoader = pluginClassLoaders.get(pluginId);
    if (classLoader == null) {
      throw new IllegalStateException("Plugin " + pluginId + " class loader is not available");
    }
    plugin.registered().forEach((group, instances) -> activateInstances(pluginId, group, instances, classLoader, activated));
  }

  private void activateInstances(
      String pluginId,
      String group,
      Set<Plugin.PluginInstance> instances,
      ClassLoader classLoader,
      Map<String, Set<Plugin.PluginInstance>> activated
  ) {
    PluginInstanceHandler handler = getHandlers().get(group);
    if (handler == null) {
      throw new IllegalStateException("Plugin instance handler not found for group: " + group);
    }
    for (Plugin.PluginInstance instance : instances) {
      try {
        if (instance.getClazz() == null) {
          instance.classes(classLoader.loadClass(instance.getName()));
        }
        log.info("正在启用插件实例：[插件:{} 分组:{} 名称:{}]", pluginId, group, instance.getName());
        handler.handle(instance);
        activated.computeIfAbsent(group, ignored -> new HashSet<>()).add(instance);
      } catch (Exception e) {
        throw new IllegalStateException("Failed to enable plugin instance " + instance.getName(), e);
      }
    }
  }

  private void markEnabled(String pluginId) {
    Plugin plugin = storage.find(pluginId);
    if (plugin != null) {
      storage.save(plugin.withStatus(PluginStatus.ENABLED));
    }
  }

  private void markFailed(String pluginId, Exception failure) {
    Plugin plugin = storage.find(pluginId);
    if (plugin != null) {
      storage.save(plugin.withFailure(failure.getMessage()));
    }
  }

  private void rollbackFailedInstall(String pluginId, Exception failure) {
    Plugin plugin = storage.find(pluginId);
    if (plugin == null) {
      closeClassLoader(pluginId);
      pluginDependencies.remove(pluginId);
      return;
    }
    try {
      publishUninstalling(plugin);
    } catch (Exception cleanupException) {
      failure.addSuppressed(cleanupException);
    }
    try {
      rollbackInstances(plugin.registered(), true);
    } catch (Exception cleanupException) {
      failure.addSuppressed(cleanupException);
    }
    storage.remove(pluginId);
    removeQueuedTasks(pluginId);
    closeClassLoader(pluginId);
    pluginDependencies.remove(pluginId);
  }

  private void rollbackBatchInstall(List<Plugin> installed, Exception failure) {
    for (int index = installed.size() - 1; index >= 0; index--) {
      try {
        rollbackPendingInstall(installed.get(index).manifest().getId());
      } catch (Exception cleanupException) {
        failure.addSuppressed(cleanupException);
      }
    }
  }

  private void rollbackPendingInstall(String pluginId) {
    Plugin plugin = storage.find(pluginId);
    removeQueuedTasks(pluginId);
    if (plugin != null) {
      rollbackInstances(plugin.registered(), true);
      storage.remove(pluginId);
    }
    closeClassLoader(pluginId);
    pluginDependencies.remove(pluginId);
  }

  private void closeClassLoader(String pluginId) {
    URLClassLoader cl = pluginClassLoaders.remove(pluginId);
    if (cl != null) {
      try {
        cl.close();
      } catch (IOException e) {
        log.warn("关闭插件类加载器失败:{}", e.getMessage());
      }
    }
  }

  private void removeQueuedTasks(String pluginId) {
    handleQueue.removeIf(task -> {
      if (pluginId.equals(task.pluginId())) {
        task.cancelled();
        saveTask(task);
        return true;
      }
      return false;
    });
  }

  private void enqueueTask(String pluginId, PluginOperation operation, Runnable action) {
    PluginTask task = new PluginTask(pluginId, operation, action);
    saveTask(task);
    handleQueue.add(task);
  }

  private void saveTask(PluginTask task) {
    taskStore.save(task.snapshot());
  }

  /**
   * Checks whether the plugin already exists in storage.
   *
   * @param plugin the plugin to check
   * @throws PluginExistException if the plugin already exists
   */
  private void checkPlugin(Plugin plugin) throws PluginExistException {
    Plugin pluginInfo = storage.find(plugin.manifest().getId());
    if (pluginInfo != null) {
      log.error("该插件已经加载完毕，已跳过加载！");
      printPluginInfo(pluginInfo.manifest());
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
      if (data == null || data.isEmpty()) {
        continue;
      }
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
      String pluginId,
      Map<String, Set<Plugin.PluginInstance>> beansRegister, ClassLoader pluginCl) throws Exception {
    Map<String, Set<Plugin.PluginInstance>> beanContexts = new Hashtable<>();
    AtomicReference<Exception> exception = new AtomicReference<>(null);
    if (beansRegister == null || beansRegister.isEmpty()) {
      return beanContexts;
    }

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
            // Load with isolated plugin classloader
            pluginInstance.classes(pluginCl.loadClass(name));
            enqueueTask(pluginId, PluginOperation.SUBMIT, () -> {
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
          if (packages.stream().anyMatch(packagePrefix -> realName.startsWith(packagePrefix)
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
   * Logs detailed information about the given plugin manifest.
   *
   * @param manifest the plugin manifest containing details such as name, version, and author
   */
  public void printPluginInfo(PluginManifest manifest) {
    log.info("====================Plugin Info====================");
    log.info("插件ID：{}", manifest.getId());
    log.info("插件名称：{}", manifest.getName());
    log.info("插件作者：{}", manifest.getAuthor());
    log.info("插件版本：{}", manifest.getVersion());
    log.info("====================Plugin Info====================");
  }

  private static final class PluginTask {

    private final String id = UUID.randomUUID().toString();
    private final String pluginId;
    private final PluginOperation operation;
    private final Runnable action;
    private final Instant createdAt = Instant.now();
    private Instant updatedAt = createdAt;
    private PluginTaskStatus status = PluginTaskStatus.PENDING;
    private int attempts;
    private String failure;

    private PluginTask(String pluginId, PluginOperation operation, Runnable action) {
      this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
      this.operation = Objects.requireNonNull(operation, "operation");
      this.action = Objects.requireNonNull(action, "action");
    }

    private String id() {
      return id;
    }

    private String pluginId() {
      return pluginId;
    }

    private Instant createdAt() {
      return createdAt;
    }

    private int attempts() {
      return attempts;
    }

    private void running() {
      attempts++;
      status = PluginTaskStatus.RUNNING;
      failure = null;
      updatedAt = Instant.now();
    }

    private void retrying(Exception exception) {
      status = PluginTaskStatus.PENDING;
      failure = exception.getMessage();
      updatedAt = Instant.now();
    }

    private void succeeded() {
      status = PluginTaskStatus.SUCCEEDED;
      failure = null;
      updatedAt = Instant.now();
    }

    private void failed(Exception exception) {
      status = PluginTaskStatus.FAILED;
      failure = exception.getMessage();
      updatedAt = Instant.now();
    }

    private void cancelled() {
      status = PluginTaskStatus.CANCELLED;
      updatedAt = Instant.now();
    }

    private void execute() {
      action.run();
    }

    private PluginTaskSnapshot snapshot() {
      return new PluginTaskSnapshot(id, pluginId, operation, status, attempts, createdAt, updatedAt, failure);
    }
  }

}
