/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.spring.configuration;

import org.simplepoint.plugin.api.PluginsManager;
import org.simplepoint.plugin.core.AbstractPluginsManager;
import org.simplepoint.plugin.spring.handle.SpringBeanPluginInstanceHandler;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration class for setting up the plugin manager and related components.
 * This configuration class initializes the PluginsManager and registers the plugin instance handler
 * to enable plugin management within a Spring application.
 */
@Configuration(proxyBeanMethods = false)
public class PluginManagerAutoConfiguration {

  private final ApplicationContext applicationContext;

  /**
   * Constructs a new PluginManagerAutoConfiguration instance.
   *
   * @param applicationContext the Spring ApplicationContext used to manage beans
   */
  public PluginManagerAutoConfiguration(ApplicationContext applicationContext) {
    this.applicationContext = applicationContext;
  }

  /**
   * Defines a PluginsManager bean.
   * This bean is responsible for managing plugins within the application.
   *
   * @return a new PluginsManager instance
   * @throws Exception if an error occurs during the creation of the PluginsManager
   */
  @Bean
  public PluginsManager pluginsManager() throws Exception {
    return new AbstractPluginsManager() {
    };
  }

  /**
   * Defines a SpringBeanPluginInstanceHandler bean and registers it with the PluginsManager.
   * The handler is responsible for managing plugin instances using the Spring ApplicationContext.
   *
   * @param pluginsManager the PluginsManager used to manage plugin handlers
   * @return a new SpringBeanPluginInstanceHandler instance
   */
  @Bean
  public SpringBeanPluginInstanceHandler pluginInstanceHandler(PluginsManager pluginsManager) {
    SpringBeanPluginInstanceHandler handler =
        new SpringBeanPluginInstanceHandler(this.applicationContext);
    pluginsManager.registerHandle(handler);
    return handler;
  }
}
