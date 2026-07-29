/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.cloud.oauth.server.client;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Security and lifecycle limits for OAuth Client ID Metadata Documents.
 */
@Component
@ConfigurationProperties(prefix = "simplepoint.security.oauth2.client-id-metadata")
public class ClientIdMetadataDocumentProperties {

  private boolean enabled = true;

  private boolean allowInsecureLocalhost;

  private Duration connectTimeout = Duration.ofSeconds(5);

  private Duration requestTimeout = Duration.ofSeconds(10);

  private Duration cacheTtl = Duration.ofMinutes(5);

  private int maxCacheEntries = 1000;

  private int maxResponseBytes = 64 * 1024;

  private int maxRedirectUris = 20;

  private int maxDocumentUriLength = 2048;

  private Set<String> allowedScopes = new LinkedHashSet<>(Set.of("mcp.invoke"));

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(final boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isAllowInsecureLocalhost() {
    return allowInsecureLocalhost;
  }

  public void setAllowInsecureLocalhost(final boolean allowInsecureLocalhost) {
    this.allowInsecureLocalhost = allowInsecureLocalhost;
  }

  public Duration getConnectTimeout() {
    return connectTimeout;
  }

  public void setConnectTimeout(final Duration connectTimeout) {
    this.connectTimeout = connectTimeout;
  }

  public Duration getRequestTimeout() {
    return requestTimeout;
  }

  public void setRequestTimeout(final Duration requestTimeout) {
    this.requestTimeout = requestTimeout;
  }

  public Duration getCacheTtl() {
    return cacheTtl;
  }

  public void setCacheTtl(final Duration cacheTtl) {
    this.cacheTtl = cacheTtl;
  }

  public int getMaxCacheEntries() {
    return maxCacheEntries;
  }

  public void setMaxCacheEntries(final int maxCacheEntries) {
    this.maxCacheEntries = maxCacheEntries;
  }

  public int getMaxResponseBytes() {
    return maxResponseBytes;
  }

  public void setMaxResponseBytes(final int maxResponseBytes) {
    this.maxResponseBytes = maxResponseBytes;
  }

  public int getMaxRedirectUris() {
    return maxRedirectUris;
  }

  public void setMaxRedirectUris(final int maxRedirectUris) {
    this.maxRedirectUris = maxRedirectUris;
  }

  public int getMaxDocumentUriLength() {
    return maxDocumentUriLength;
  }

  public void setMaxDocumentUriLength(final int maxDocumentUriLength) {
    this.maxDocumentUriLength = maxDocumentUriLength;
  }

  public Set<String> getAllowedScopes() {
    return allowedScopes;
  }

  public void setAllowedScopes(final Set<String> allowedScopes) {
    this.allowedScopes = allowedScopes == null
        ? new LinkedHashSet<>() : new LinkedHashSet<>(allowedScopes);
  }
}
