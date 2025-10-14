/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.io.Serializable;
import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import lombok.Data;
import lombok.Getter;

/**
 * Represents plugin information.
 * This record includes the plugin path, metadata, and registered bean instances.
 *
 * @param path       the URI location of the plugin
 * @param metadata   the metadata information of the plugin
 * @param registered the map of registered beans
 */
public record Plugin(
    URI path,
    PluginMetadata metadata,
    Map<String, Set<PluginInstance>> registered
) implements Serializable {

  /**
   * Retrieves the map of registered beans as an unmodifiable map.
   *
   * @return the unmodifiable map of registered beans
   */
  @Override
  public Map<String, Set<PluginInstance>> registered() {
    return Collections.unmodifiableMap(registered);
  }

  /**
   * Represents the metadata information of a plugin.
   * Includes attributes such as plugin ID, name, version, author, and other details.
   */
  @Data
  public static final class PluginMetadata implements Serializable {
    private String pid;
    private String name;
    private String version;
    private String author;
    private String declaration;
    private String email;
    private String document;
    private String phone;
    private String packageName;
    private String autoRegister;
    private Map<String, String> packageScan;
    private Map<String, Set<PluginInstance>> instances;
  }

  /**
   * Represents an instance of a plugin bean.
   * Includes the bean name, class name, group, and provides methods for managing the instance.
   */
  @Getter
  public static class PluginInstance implements Serializable {
    private final String name;
    private final String beanClassName;
    private final String beanGroup;

    @JsonIgnore
    private Class<?> clazz;

    @JsonIgnore
    private Object instance;

    /**
     * Constructs a new PluginInstance with the given details.
     *
     * @param name          the name of the bean
     * @param beanClassName the class name of the bean
     * @param beanGroup     the group to which the bean belongs
     */
    public PluginInstance(String name, String beanClassName, String beanGroup) {
      this.name = name;
      this.beanClassName = beanClassName;
      this.beanGroup = beanGroup;
    }

    /**
     * Sets the instance of the bean if it has not been set already.
     *
     * @param instance the instance to set
     */
    public void instance(Object instance) {
      if (this.instance == null) {
        this.instance = instance;
      }
    }

    /**
     * Sets the class of the bean if it has not been set already.
     *
     * @param clazz the class to set
     */
    public void classes(Class<?> clazz) {
      if (this.clazz == null) {
        this.clazz = clazz;
      }
    }

    /**
     * Clears the instance and class of the bean.
     * Invokes garbage collection to free resources.
     */
    public void clearInstance() {
      this.instance = null;
      this.clazz = null;
      System.gc();
    }
  }
}



