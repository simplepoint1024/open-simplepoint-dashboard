/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.core;

/**
 * Runtime versions used to verify plugin compatibility.
 *
 * @param coreVersion        current backend plugin runtime version
 * @param frontendSdkVersion current frontend plugin SDK version
 */
public record PluginRuntimeVersions(String coreVersion, String frontendSdkVersion) {

  private static final String CORE_VERSION_PROPERTY = "plugin.runtime.core-version";
  private static final String FRONTEND_SDK_VERSION_PROPERTY = "plugin.runtime.frontend-sdk-version";

  /**
   * Detects runtime versions from system properties and package metadata.
   *
   * @return detected runtime versions
   */
  public static PluginRuntimeVersions detect() {
    Package runtimePackage = PluginRuntimeVersions.class.getPackage();
    return new PluginRuntimeVersions(
        firstNonBlank(System.getProperty(CORE_VERSION_PROPERTY), runtimePackage.getImplementationVersion()),
        System.getProperty(FRONTEND_SDK_VERSION_PROPERTY)
    );
  }

  private static String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }
}
