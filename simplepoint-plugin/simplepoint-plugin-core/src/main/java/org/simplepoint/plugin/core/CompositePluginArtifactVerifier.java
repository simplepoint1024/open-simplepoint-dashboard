/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.core;

import java.util.Collection;
import java.util.List;
import org.simplepoint.plugin.api.PluginArtifact;
import org.simplepoint.plugin.api.manifest.PluginManifest;

/**
 * Runs multiple artifact verifiers in order.
 */
public final class CompositePluginArtifactVerifier implements PluginArtifactVerifier {

  private final List<PluginArtifactVerifier> verifiers;

  private CompositePluginArtifactVerifier(Collection<PluginArtifactVerifier> verifiers) {
    this.verifiers = verifiers == null ? List.of() : List.copyOf(verifiers);
  }

  /**
   * Creates a verifier from the supplied verifier list.
   *
   * @param verifiers artifact verifiers
   * @return a no-op verifier when the list is empty, otherwise a composite verifier
   */
  public static PluginArtifactVerifier of(Collection<PluginArtifactVerifier> verifiers) {
    if (verifiers == null || verifiers.isEmpty()) {
      return NoopPluginArtifactVerifier.INSTANCE;
    }
    return new CompositePluginArtifactVerifier(verifiers);
  }

  @Override
  public void verify(PluginArtifact artifact, PluginManifest manifest) {
    for (PluginArtifactVerifier verifier : verifiers) {
      verifier.verify(artifact, manifest);
    }
  }
}
