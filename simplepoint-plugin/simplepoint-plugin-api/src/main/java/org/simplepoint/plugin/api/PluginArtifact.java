/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.api;

import java.io.Serializable;
import java.net.URI;

/**
 * Immutable metadata for a plugin artifact.
 *
 * @param uri    artifact URI
 * @param size   artifact size in bytes, or {@code -1} when unknown
 * @param sha256 artifact SHA-256 hex digest, or {@code null} when unknown
 */
public record PluginArtifact(
    URI uri,
    long size,
    String sha256
) implements Serializable {

  /**
   * Creates artifact metadata with unknown size and digest.
   *
   * @param uri artifact URI
   * @return artifact metadata
   */
  public static PluginArtifact unknown(URI uri) {
    return new PluginArtifact(uri, -1, null);
  }
}
