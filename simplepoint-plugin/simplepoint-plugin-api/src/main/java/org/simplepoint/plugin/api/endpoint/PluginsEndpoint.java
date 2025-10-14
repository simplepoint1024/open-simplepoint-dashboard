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
import java.util.List;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.api.Plugin;
import org.simplepoint.plugin.api.PluginsManager;
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
    File tempFile = File.createTempFile(plugin.getName(), ".jar");
    plugin.transferTo(tempFile);
    Plugin install = this.pluginsManager.install(tempFile.toURI());
    this.pluginsManager.submit();
    return Response.okay(install);
  }

  /**
   * Uninstalls a plugin by its package name.
   *
   * @param pkg the package name of the plugin to uninstall
   * @return a generic response indicating the result of the operation
   */
  @DeleteMapping("${plugin.endpoint.uninstall:}")
  @Operation(summary = "卸载插件", description = "通过插件的包名进行卸载")
  public Response<?> uninstall(
      @Parameter(description = "插件的包名", required = true)
      @RequestParam("pkg")
      String pkg
  ) {
    this.pluginsManager.uninstall(pkg);
    return Response.okay();
  }

  /**
   * Lists all installed plugins.
   *
   * @return the response containing a list of installed plugins
   */
  @GetMapping("${plugin.endpoint.plugins:}")
  @Operation(summary = "查询插件", description = "查询所有已安装的插件")
  public Response<List<Plugin>> plugins() {
    return Response.okay(this.pluginsManager.getStorage().list());
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
}
