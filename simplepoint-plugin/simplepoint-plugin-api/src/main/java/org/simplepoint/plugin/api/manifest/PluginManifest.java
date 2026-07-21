/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.api.manifest;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Data;

/**
 * Declarative plugin manifest.
 *
 * <p>The manifest is the stable contract used by the plugin control plane. It
 * describes backend artifacts, frontend remotes, resources,
 * i18n bundles, and runtime compatibility without requiring plugin code to run.
 */
@Data
public class PluginManifest implements Serializable {

  private String id;
  private String name;
  private String version;
  private String description;
  private String author;
  private String coreVersion;
  private String frontendSdkVersion;
  private List<PluginDependency> dependencies;
  private Backend backend;
  private Frontend frontend;
  private List<ResourceContribution> resources;
  private List<I18nContribution> i18n;
  private Map<String, Object> configSchema;

  /**
   * Dependency on another plugin.
   */
  @Data
  public static class PluginDependency implements Serializable {
    private String id;
    private String version;
    private Boolean optional;
  }

  /**
   * Backend runtime contribution block.
   */
  @Data
  public static class Backend implements Serializable {
    private List<ServiceArtifact> services;
    private Map<String, String> packageScan;
    private Map<String, Set<PluginInstanceContribution>> instances;
  }

  /**
   * Backend service artifact requirement.
   */
  @Data
  public static class ServiceArtifact implements Serializable {
    private String name;
    private String artifact;
    private String runtimeMode;
    private Boolean required;
  }

  /**
   * Explicit backend instance contribution.
   */
  @Data
  public static class PluginInstanceContribution implements Serializable {
    private String name;
    private String className;
    private String group;
  }

  /**
   * Frontend contribution block.
   */
  @Data
  public static class Frontend implements Serializable {
    private List<RemoteContribution> remotes;
  }

  /**
   * Micro frontend remote contribution.
   */
  @Data
  public static class RemoteContribution implements Serializable {
    private String name;
    private String entry;
    private String module;
    private String version;
  }

  /**
   * RBAC resource contribution.
   */
  @Data
  public static class ResourceContribution implements Serializable {
    private String code;
    private String name;
    private String alias;
    private String title;
    private String label;
    private String type;
    private String parent;
    private String path;
    private String component;
    private String icon;
    private String routeKind;
    private String method;
    private String pattern;
    private String description;
    private Integer sort;
    private Boolean publicAccess;
    private Boolean requireOrgTenant;
    private Set<String> scopeTypes;
    private Boolean grantable;
    private Boolean disabled;
    private Boolean danger;
    private List<ResourceContribution> children;
  }

  /**
   * i18n bundle contribution.
   */
  @Data
  public static class I18nContribution implements Serializable {
    private String locale;
    private String namespace;
    private String path;
  }
}
