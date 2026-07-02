/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.core;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.simplepoint.plugin.api.PluginArtifact;
import org.simplepoint.plugin.api.manifest.PluginManifest;

/**
 * Verifies plugin artifacts against configured trusted SHA-256 digests.
 */
public final class TrustedSha256PluginArtifactVerifier implements PluginArtifactVerifier {

  private static final Pattern SHA256 = Pattern.compile("^[0-9a-fA-F]{64}$");

  private final Map<String, Set<String>> trustedSha256;
  private final boolean requireKnownSha256;

  /**
   * Creates a trusted SHA-256 verifier.
   *
   * @param trustedSha256      plugin id to trusted digest list
   * @param requireKnownSha256 whether every plugin must have at least one configured trusted digest
   */
  public TrustedSha256PluginArtifactVerifier(
      Map<String, ? extends Collection<String>> trustedSha256,
      boolean requireKnownSha256
  ) {
    this.trustedSha256 = normalize(trustedSha256);
    this.requireKnownSha256 = requireKnownSha256;
  }

  @Override
  public void verify(PluginArtifact artifact, PluginManifest manifest) {
    String pluginId = manifest.getId();
    Set<String> trustedDigests = trustedSha256.get(pluginId);
    if (trustedDigests == null || trustedDigests.isEmpty()) {
      if (requireKnownSha256) {
        throw new IllegalStateException("Plugin " + pluginId + " does not have a trusted SHA-256 digest configured");
      }
      return;
    }
    String actual = artifact.sha256();
    if (actual == null || actual.isBlank()) {
      throw new IllegalStateException("Plugin " + pluginId + " does not have a computed SHA-256 digest");
    }
    if (!trustedDigests.contains(actual.toLowerCase(Locale.ROOT))) {
      throw new IllegalStateException("Plugin " + pluginId + " SHA-256 digest is not trusted");
    }
  }

  private Map<String, Set<String>> normalize(Map<String, ? extends Collection<String>> source) {
    if (source == null || source.isEmpty()) {
      return Map.of();
    }
    Map<String, Set<String>> result = new LinkedHashMap<>();
    source.forEach((pluginId, digests) -> {
      if (pluginId == null || pluginId.isBlank()) {
        throw new IllegalArgumentException("Trusted SHA-256 plugin id must not be blank");
      }
      Set<String> normalized = new LinkedHashSet<>();
      if (digests != null) {
        digests.forEach(digest -> normalized.add(normalizeDigest(pluginId, digest)));
      }
      result.put(pluginId, Set.copyOf(normalized));
    });
    return Map.copyOf(result);
  }

  private String normalizeDigest(String pluginId, String digest) {
    if (digest == null || !SHA256.matcher(digest.trim()).matches()) {
      throw new IllegalArgumentException("Trusted SHA-256 digest for plugin " + pluginId + " must be 64 hex chars");
    }
    return digest.trim().toLowerCase(Locale.ROOT);
  }
}
