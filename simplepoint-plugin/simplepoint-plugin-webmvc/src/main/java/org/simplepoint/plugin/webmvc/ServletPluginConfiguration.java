/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.webmvc;

import org.simplepoint.plugin.api.PluginsManager;
import org.simplepoint.plugin.spring.handle.SpringBeanPluginInstanceHandler;
import org.simplepoint.plugin.webmvc.handler.ServletMappingPluginInstanceHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class for setting up the servlet plugin instance handler.
 * This configuration integrates the ServletMappingPluginInstanceHandler into the plugin system,
 * enabling dynamic servlet mapping functionality in the application.
 */
@Configuration(proxyBeanMethods = false)
public class ServletPluginConfiguration {

  /**
   * Defines a ServletMappingPluginInstanceHandler bean.
   * This bean handles servlet mappings dynamically and
   * registers the handler with the PluginsManager.
   *
   * @param pluginsManager                  the PluginsManager used to manage plugin handlers
   * @param springBeanPluginInstanceHandler the SpringBeanPluginInstanceHandler
   *                                        for managing Spring beans
   * @return a configured ServletMappingPluginInstanceHandler instance
   */
  @Bean
  public ServletMappingPluginInstanceHandler servletMappingPluginInstanceHandler(
      PluginsManager pluginsManager,
      SpringBeanPluginInstanceHandler springBeanPluginInstanceHandler) {
    ServletMappingPluginInstanceHandler mappingPluginInstanceHandler =
        new ServletMappingPluginInstanceHandler(springBeanPluginInstanceHandler);

    // Set the order of the mapping handler to 0 to prioritize it
    mappingPluginInstanceHandler.setOrder(0);

    // Register the mapping handler with the PluginsManager
    pluginsManager.registerHandle(mappingPluginInstanceHandler);

    return mappingPluginInstanceHandler;
  }
}
