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
 * describes backend artifacts, frontend remotes, menus, permissions, features,
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
  private List<MenuContribution> menus;
  private List<PermissionContribution> permissions;
  private List<FeatureContribution> features;
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
   * RBAC menu contribution.
   */
  @Data
  public static class MenuContribution implements Serializable {
    private String authority;
    private String title;
    private String label;
    private String path;
    private String component;
    private String parent;
    private String icon;
    private Integer sort;
    private String type;
    private Boolean danger;
    private Boolean disabled;
    private Set<String> featureCodes;
  }

  /**
   * RBAC permission contribution.
   */
  @Data
  public static class PermissionContribution implements Serializable {
    private String authority;
    private String name;
    private String resource;
    private String description;
    private Integer type;
  }

  /**
   * RBAC feature contribution.
   */
  @Data
  public static class FeatureContribution implements Serializable {
    private String code;
    private String name;
    private String description;
    private Integer sort;
    private Boolean publicAccess;
    private Boolean requireOrgTenant;
    private Set<String> permissions;
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
