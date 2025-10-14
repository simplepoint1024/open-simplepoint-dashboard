/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.webmvc.handler;

import java.util.List;
import org.simplepoint.plugin.api.Plugin;
import org.simplepoint.plugin.api.PluginInstanceHandler;
import org.simplepoint.plugin.spring.handle.SpringBeanPluginInstanceHandler;
import org.springframework.beans.BeansException;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * A handler for managing servlet mappings dynamically in a Spring environment.
 * This class extends RequestMappingHandlerMapping and implements PluginInstanceHandler
 * to provide functionality for dynamically registering, unregistering, and managing
 * plugin-based servlet mappings.
 */
public class ServletMappingPluginInstanceHandler extends RequestMappingHandlerMapping
    implements PluginInstanceHandler {

  private final SpringBeanPluginInstanceHandler handler;

  /**
   * Constructs a new ServletMappingPluginInstanceHandler instance.
   *
   * @param handler the SpringBeanPluginInstanceHandler used for managing beans
   * @throws BeansException if an error occurs during bean handling
   */
  public ServletMappingPluginInstanceHandler(SpringBeanPluginInstanceHandler handler)
      throws BeansException {
    this.handler = handler;
  }

  /**
   * Unregisters all servlet mappings associated with the specified bean name.
   *
   * @param beanName the name of the bean whose mappings are to be unregistered
   */
  public void unregisterMapping(String beanName) {
    Object bean = this.handler.applicationContext().getBean(beanName);
    Class<?> targetClass = bean.getClass();

    // Iterate through all user-declared methods and unregister mappings
    ReflectionUtils.doWithMethods(targetClass, method -> {
      RequestMappingInfo requestMappingInfo =
          getMappingForMethod(ClassUtils.getMostSpecificMethod(method, targetClass), targetClass);
      if (requestMappingInfo != null) {
        unregisterMapping(requestMappingInfo);
      }
    }, ReflectionUtils.USER_DECLARED_METHODS);
  }

  /**
   * Retrieves the groups supported by this handler.
   *
   * @return a list containing the group names "controller" and "endpoint"
   */
  @Override
  public List<String> groups() {
    return List.of("controller", "endpoint");
  }

  /**
   * Handles the given plugin instance by delegating
   * the handling to the SpringBeanPluginInstanceHandler.
   *
   * @param instance the plugin instance to handle
   */
  @Override
  public void handle(Plugin.PluginInstance instance) {
    this.handler.handle(instance);
    super.processCandidateBean(instance.getName());
  }

  /**
   * Rolls back changes made by the plugin instance.
   * This includes unregistering servlet mappings and delegating the rollback process
   * to the SpringBeanPluginInstanceHandler.
   *
   * @param instance the plugin instance to roll back
   */
  @Override
  public void rollback(Plugin.PluginInstance instance) {
    this.unregisterMapping(instance.getName());
    this.handler.rollback(instance);
  }
}
