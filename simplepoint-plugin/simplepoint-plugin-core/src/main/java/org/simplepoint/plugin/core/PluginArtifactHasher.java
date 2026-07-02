/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.core;

import java.io.IOException;
import java.net.URI;
import org.simplepoint.plugin.api.PluginArtifact;

/**
 * Computes metadata for plugin artifacts.
 */
interface PluginArtifactHasher {

  /**
   * Computes artifact metadata for the given URI.
   *
   * @param uri plugin artifact URI
   * @return plugin artifact metadata
   * @throws IOException if the artifact cannot be read
   */
  PluginArtifact hash(URI uri) throws IOException;
}
