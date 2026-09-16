package org.simplepoint.plugin.ai.runtime.api.service;

import org.simplepoint.plugin.ai.runtime.api.model.RuntimeImageResolution;

/** Resolves an allowed OCI tag to the manifest digest observed by Runtime. */
public interface AiRuntimeImageResolver {

  /** Pulls, inspects and returns one immutable OCI digest. */
  RuntimeImageResolution resolve(String imageReference);
}
