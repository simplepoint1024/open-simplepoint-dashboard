/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.api.endpoint;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.api.Plugin;
import org.simplepoint.plugin.api.PluginsManager;
import org.simplepoint.plugin.api.exception.PluginExistException;
import org.simplepoint.plugin.api.management.PluginInstallPlan;
import org.simplepoint.plugin.api.management.PluginOperationAudit;
import org.simplepoint.plugin.api.management.PluginRegistryView;
import org.simplepoint.plugin.api.management.PluginTaskSnapshot;
import org.simplepoint.plugin.api.management.PluginUpgradePlan;
import org.simplepoint.plugin.api.manifest.PluginManifest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * A REST controller for managing plugin-related operations.
 * This controller provides endpoints for installing, uninstalling,
 * and listing plugins. It also supports auto-loading plugins
 * from a specified directory during application startup.
 */
@RequestMapping("/plugins")
@RestController
@ConditionalOnProperty(name = "plugin.endpoint.enable", havingValue = "true", matchIfMissing = true)
@Tag(name = "插件管理", description = "插件的安装、卸载、查询等操作")
public class PluginsEndpoint implements ApplicationRunner {

  private final PluginsManager pluginsManager;
  private final String pluginsDir;
  private final Boolean autoloader;

  /**
   * Constructs a new PluginsEndpoint instance.
   *
   * @param pluginsManager the PluginsManager used for plugin operations
   * @param pluginsDir     the directory path for plugin auto-loading
   * @param autoloader     whether the auto-loader feature is enabled
   */
  public PluginsEndpoint(
      PluginsManager pluginsManager,
      @Value("${plugin.autoloader.path:plugins/}") String pluginsDir,
      @Value("${plugin.autoloader.enable:true}") Boolean autoloader
  ) {
    this.pluginsManager = pluginsManager;
    this.pluginsDir = pluginsDir;
    this.autoloader = autoloader;
  }

  /**
   * Installs a plugin from the provided MultipartFile.
   * The plugin is transferred to the auto-loader directory and installed.
   *
   * @param plugin the MultipartFile representing the plugin to install
   * @return the response containing the installed Plugin details
   * @throws Exception if an error occurs during file transfer or installation
   */
  @PostMapping("${plugin.endpoint.install:}")
  @Operation(summary = "安装插件", description = "上传一个插件的jar包进行安装")
  public Response<Plugin> install(
      @Parameter(description = "插件的jar包", required = true)
      @RequestParam("plugin")
      MultipartFile plugin
  ) throws Exception {
    Path tempFile = copyUploadToTemp(plugin);
    try {
      PluginManifest manifest = this.pluginsManager.inspect(tempFile.toUri());
      ensurePluginNotInstalled(manifest);
      Path target = persistUpload(tempFile, manifest);
      try {
        Plugin install = this.pluginsManager.install(target.toUri());
        this.pluginsManager.submit();
        return Response.okay(install);
      } catch (Exception e) {
        cleanupFailedInstall(manifest, target, e);
        throw e;
      }
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  /**
   * Plans a plugin installation without persisting or installing the upload.
   *
   * @param plugin the MultipartFile representing the plugin to plan
   * @return the response containing the installation plan
   * @throws IOException if the upload cannot be copied to a temporary file
   */
  @PostMapping("${plugin.endpoint.plan:/plan}")
  @Operation(summary = "预检查插件安装", description = "上传插件 jar 包并返回安装顺序、依赖解析和阻断原因，不安装插件")
  public Response<PluginInstallPlan> plan(
      @Parameter(description = "插件的jar包", required = true)
      @RequestParam("plugin")
      MultipartFile plugin
  ) throws IOException {
    Path tempFile = copyUploadToTemp(plugin);
    try {
      return Response.okay(this.pluginsManager.planInstall(tempFile.toUri()));
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  /**
   * Plans a plugin upgrade without persisting or installing the upload.
   *
   * @param plugin the MultipartFile representing the plugin upgrade archive
   * @return the response containing the upgrade plan
   * @throws IOException if the upload cannot be copied to a temporary file
   */
  @PostMapping("${plugin.endpoint.plan-upgrade:/upgrade/plan}")
  @Operation(summary = "预检查插件升级", description = "上传插件新版本 jar 包并返回升级阻断原因，不持久化或升级插件")
  public Response<PluginUpgradePlan> planUpgrade(
      @Parameter(description = "插件的新版本 jar 包", required = true)
      @RequestParam("plugin")
      MultipartFile plugin
  ) throws IOException {
    Path tempFile = copyUploadToTemp(plugin);
    try {
      return Response.okay(this.pluginsManager.planUpgrade(tempFile.toUri()));
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  /**
   * Upgrades an installed plugin from the provided MultipartFile.
   *
   * @param plugin the MultipartFile representing the new plugin archive
   * @return the response containing the upgraded Plugin details
   * @throws Exception if an error occurs during file transfer or upgrade
   */
  @PostMapping("${plugin.endpoint.upgrade:/upgrade}")
  @Operation(summary = "升级插件", description = "上传一个插件的新版本 jar 包进行升级")
  public Response<Plugin> upgrade(
      @Parameter(description = "插件的新版本 jar 包", required = true)
      @RequestParam("plugin")
      MultipartFile plugin
  ) throws Exception {
    Path tempFile = copyUploadToTemp(plugin);
    try {
      PluginManifest manifest = this.pluginsManager.inspect(tempFile.toUri());
      Plugin current = ensurePluginInstalled(manifest);
      Path target = preparePluginDirectory().resolve(pluginFileName(manifest)).normalize();
      if (target.toUri().equals(current.path())) {
        throw new IllegalStateException("升级包会覆盖当前插件文件，请提升插件版本或先卸载后重新安装。");
      }
      Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
      try {
        Plugin upgraded = this.pluginsManager.upgrade(target.toUri());
        deletePreviousArtifact(current.path(), target);
        return Response.okay(upgraded);
      } catch (Exception e) {
        cleanupFailedUpgrade(current, target, e);
        throw e;
      }
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  /**
   * Enables a disabled plugin by its manifest id.
   *
   * @param id the manifest id of the plugin to enable
   * @return the response containing the enabled plugin
   */
  @PostMapping("${plugin.endpoint.enable-plugin:/enable}")
  @Operation(summary = "启用插件", description = "通过插件 Manifest ID 重新启用已禁用的插件")
  public Response<Plugin> enable(
      @Parameter(description = "插件 Manifest ID", required = true)
      @RequestParam("id")
      String id
  ) {
    return Response.okay(this.pluginsManager.enable(id));
  }

  /**
   * Disables an enabled plugin by its manifest id.
   *
   * @param id the manifest id of the plugin to disable
   * @return the response containing the disabled plugin
   */
  @PostMapping("${plugin.endpoint.disable-plugin:/disable}")
  @Operation(summary = "禁用插件", description = "通过插件 Manifest ID 禁用插件，但保留插件包和类加载器")
  public Response<Plugin> disable(
      @Parameter(description = "插件 Manifest ID", required = true)
      @RequestParam("id")
      String id
  ) {
    return Response.okay(this.pluginsManager.disable(id));
  }

  /**
   * Uninstalls a plugin by its manifest id.
   *
   * @param id the manifest id of the plugin to uninstall
   * @return a generic response indicating the result of the operation
   */
  @DeleteMapping("${plugin.endpoint.uninstall:}")
  @Operation(summary = "卸载插件", description = "通过插件 Manifest ID 进行卸载")
  public Response<?> uninstall(
      @Parameter(description = "插件 Manifest ID", required = true)
      @RequestParam("id")
      String id
  ) {
    this.pluginsManager.uninstall(id);
    return Response.okay();
  }

  /**
   * Returns plugin registry state.
   *
   * @return the response containing plugin registry state
   */
  @GetMapping("${plugin.endpoint.plugins:}")
  @Operation(summary = "查询插件", description = "查询插件状态、依赖图和可操作性")
  public Response<PluginRegistryView> plugins() {
    return Response.okay(this.pluginsManager.registry());
  }

  /**
   * Returns plugin operation audit entries.
   *
   * @return the response containing plugin operation audit entries
   */
  @GetMapping("${plugin.endpoint.operations:/operations}")
  @Operation(summary = "查询插件操作审计", description = "查询插件安装、启停、升级、卸载等操作记录")
  public Response<List<PluginOperationAudit>> operations() {
    return Response.okay(this.pluginsManager.operationAudits());
  }

  /**
   * Returns plugin runtime registration tasks.
   *
   * @return the response containing plugin runtime task snapshots
   */
  @GetMapping("${plugin.endpoint.tasks:/tasks}")
  @Operation(summary = "查询插件运行时任务", description = "查询插件提交、注册、回滚相关的运行时任务状态")
  public Response<List<PluginTaskSnapshot>> tasks() {
    return Response.okay(this.pluginsManager.operationTasks());
  }

  /**
   * Auto-loads plugins from the specified directory during application startup.
   *
   * @param args the application arguments
   * @throws Exception if an error occurs during plugin installation
   */
  @Override
  public void run(ApplicationArguments args) throws Exception {
    if (this.autoloader) {
      this.pluginsManager.installAll(new File(this.pluginsDir));
      this.pluginsManager.submit();
    }
  }

  private void ensurePluginNotInstalled(PluginManifest manifest) throws PluginExistException {
    Plugin installed = this.pluginsManager.getStorage().find(manifest.getId());
    if (installed != null) {
      throw new PluginExistException("插件 " + manifest.getId() + " 已安装，终止本次上传。", installed);
    }
  }

  private Plugin ensurePluginInstalled(PluginManifest manifest) {
    Plugin installed = this.pluginsManager.getStorage().find(manifest.getId());
    if (installed == null) {
      throw new IllegalStateException("插件 " + manifest.getId() + " 尚未安装，无法升级。");
    }
    return installed;
  }

  private Path copyUploadToTemp(MultipartFile plugin) throws IOException {
    Path tempFile = Files.createTempFile("simplepoint-plugin-upload-", ".jar");
    try (InputStream input = plugin.getInputStream()) {
      Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING);
    }
    return tempFile;
  }

  private Path persistUpload(Path tempFile, PluginManifest manifest) throws IOException {
    Path target = preparePluginDirectory().resolve(pluginFileName(manifest)).normalize();
    Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
    return target;
  }

  private Path preparePluginDirectory() throws IOException {
    Path directory = Path.of(this.pluginsDir).toAbsolutePath().normalize();
    Files.createDirectories(directory);
    if (!Files.isDirectory(directory)) {
      throw new NotDirectoryException(directory.toString());
    }
    return directory;
  }

  private String pluginFileName(PluginManifest manifest) {
    return sanitizeFileSegment(manifest.getId()) + "-"
        + sanitizeFileSegment(manifest.getVersion()) + ".jar";
  }

  private String sanitizeFileSegment(String value) {
    return value.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  private void cleanupFailedInstall(PluginManifest manifest, Path target, Exception failure) {
    Plugin installed = this.pluginsManager.getStorage().find(manifest.getId());
    boolean ownedByCurrentUpload = installed == null || target.toUri().equals(installed.path());
    if (!ownedByCurrentUpload) {
      return;
    }
    if (installed != null) {
      try {
        this.pluginsManager.uninstall(manifest.getId());
      } catch (Exception cleanupException) {
        failure.addSuppressed(cleanupException);
      }
    }
    try {
      Files.deleteIfExists(target);
    } catch (IOException cleanupException) {
      failure.addSuppressed(cleanupException);
    }
  }

  private void cleanupFailedUpgrade(Plugin current, Path target, Exception failure) {
    if (!target.toUri().equals(current.path())) {
      try {
        Files.deleteIfExists(target);
      } catch (IOException cleanupException) {
        failure.addSuppressed(cleanupException);
      }
    }
  }

  private void deletePreviousArtifact(URI previousUri, Path currentArtifact) throws IOException {
    if (previousUri == null || previousUri.getScheme() == null
        || !"file".equalsIgnoreCase(previousUri.getScheme())) {
      return;
    }
    Path previousArtifact = Path.of(previousUri).toAbsolutePath().normalize();
    if (!previousArtifact.equals(currentArtifact.toAbsolutePath().normalize())) {
      Files.deleteIfExists(previousArtifact);
    }
  }
}
