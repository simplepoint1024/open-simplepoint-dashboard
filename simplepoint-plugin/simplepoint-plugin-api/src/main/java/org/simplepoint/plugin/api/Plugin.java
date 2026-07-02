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
import java.util.Objects;
import java.util.Set;
import lombok.Getter;
import org.simplepoint.plugin.api.manifest.PluginManifest;

/**
 * Represents plugin information.
 * This record includes the plugin path, manifest, and registered bean instances.
 *
 * @param path       the URI location of the plugin
 * @param manifest   the declarative plugin manifest
 * @param registered the map of registered beans
 * @param status     the current plugin runtime status
 * @param failure    the latest failure message, if any
 * @param artifact   plugin artifact metadata
 */
public record Plugin(
    URI path,
    PluginManifest manifest,
    Map<String, Set<PluginInstance>> registered,
    PluginStatus status,
    String failure,
    PluginArtifact artifact
) implements Serializable {

  /**
   * Creates a plugin instance.
   */
  public Plugin {
    artifact = artifact == null ? PluginArtifact.unknown(path) : artifact;
  }

  /**
   * Constructs a resolved plugin without a failure message.
   *
   * @param path       the URI location of the plugin
   * @param manifest   the declarative plugin manifest
   * @param registered the map of registered beans
   */
  public Plugin(URI path, PluginManifest manifest, Map<String, Set<PluginInstance>> registered) {
    this(path, manifest, registered, PluginStatus.RESOLVED, null, PluginArtifact.unknown(path));
  }

  /**
   * Constructs a resolved plugin with artifact metadata.
   *
   * @param artifact   plugin artifact metadata
   * @param manifest   the declarative plugin manifest
   * @param registered the map of registered beans
   */
  public Plugin(PluginArtifact artifact, PluginManifest manifest, Map<String, Set<PluginInstance>> registered) {
    this(artifact.uri(), manifest, registered, PluginStatus.RESOLVED, null, artifact);
  }

  /**
   * Creates a copy with the specified status.
   *
   * @param status target status
   * @return plugin copy
   */
  public Plugin withStatus(PluginStatus status) {
    return new Plugin(path, manifest, registered, status, null, artifact);
  }

  /**
   * Creates a failed plugin copy carrying the failure message.
   *
   * @param failure failure message
   * @return plugin copy
   */
  public Plugin withFailure(String failure) {
    return new Plugin(path, manifest, registered, PluginStatus.FAILED, failure, artifact);
  }

  /**
   * Retrieves the map of registered beans as an unmodifiable map.
   *
   * @return the unmodifiable map of registered beans
   */
  @Override
  public Map<String, Set<PluginInstance>> registered() {
    return Collections.unmodifiableMap(Objects.requireNonNullElse(registered, Map.of()));
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
     * Clears the runtime instance while keeping the loaded class.
     */
    public void clearRuntimeInstance() {
      this.instance = null;
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
