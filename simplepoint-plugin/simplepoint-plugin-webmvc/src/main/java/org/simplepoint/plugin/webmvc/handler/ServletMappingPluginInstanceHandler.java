/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.webmvc.handler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.api.Plugin;
import org.simplepoint.plugin.api.PluginInstanceHandler;
import org.simplepoint.plugin.spring.handle.SpringBeanPluginInstanceHandler;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;
import org.springframework.web.method.HandlerMethod;
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
    ApplicationContext ctx = this.handler.applicationContext();
    Map<String, RequestMappingHandlerMapping> all = ctx.getBeansOfType(RequestMappingHandlerMapping.class);
    // Also include this mapping instance
    if (!all.containsValue(this)) {
      all.put("_self", this);
    }
    all.values().forEach(m -> unregisterFrom(m, beanName));
  }

  /**
   * Unregisters servlet mappings from the specified RequestMappingHandlerMapping
   * that are associated with the given bean name.
   *
   * @param mapping  the RequestMappingHandlerMapping to unregister from
   * @param beanName the name of the bean whose mappings are to be unregistered
   */
  private void unregisterFrom(RequestMappingHandlerMapping mapping, String beanName) {
    List<RequestMappingInfo> toRemove = new ArrayList<>();
    Class<?> currentType = null;
    try {
      currentType = this.handler.applicationContext().getType(beanName);
      if (currentType != null) {
        currentType = ClassUtils.getUserClass(currentType);
      }
    } catch (Exception e) {
      logger.warn(e.getMessage());
    }

    for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
      HandlerMethod hm = entry.getValue();
      Object bean = hm.getBean();
      boolean match = false;
      if (bean instanceof String name) {
        match = beanName.equals(name);
      } else {
        Class<?> mappedType = hm.getBeanType();
        String mappedName = mappedType.getName();
        if (mappedName.equals(beanName)) {
          match = true;
        } else if (currentType != null && mappedName.equals(currentType.getName())) {
          match = true;
        }
      }
      if (match) {
        toRemove.add(entry.getKey());
      }
    }
    toRemove.forEach(mapping::unregisterMapping);
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
    // Defensive: pre-clean any stale mappings with the same bean name before re-registering
    this.unregisterMapping(instance.getName());
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
