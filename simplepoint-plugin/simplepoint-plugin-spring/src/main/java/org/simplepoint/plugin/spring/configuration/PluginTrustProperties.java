/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.spring.configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for plugin artifact trust checks.
 */
@ConfigurationProperties(prefix = "plugin.trust")
public class PluginTrustProperties {

  private boolean requireKnownSha256;
  private Map<String, List<String>> sha256 = new LinkedHashMap<>();

  /**
   * Whether every plugin artifact must have a configured trusted SHA-256 digest.
   *
   * @return whether strict trusted digest mode is enabled
   */
  public boolean isRequireKnownSha256() {
    return requireKnownSha256;
  }

  /**
   * Sets strict trusted digest mode.
   *
   * @param requireKnownSha256 whether every plugin artifact must have a configured trusted SHA-256 digest
   */
  public void setRequireKnownSha256(boolean requireKnownSha256) {
    this.requireKnownSha256 = requireKnownSha256;
  }

  /**
   * Returns trusted SHA-256 digests grouped by plugin id.
   *
   * @return trusted SHA-256 digests
   */
  public Map<String, List<String>> getSha256() {
    return sha256;
  }

  /**
   * Sets trusted SHA-256 digests grouped by plugin id.
   *
   * @param sha256 trusted SHA-256 digests
   */
  public void setSha256(Map<String, List<String>> sha256) {
    this.sha256 = sha256 == null ? new LinkedHashMap<>() : new LinkedHashMap<>(sha256);
  }
}
