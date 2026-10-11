package org.simplepoint.plugin.dna.runtime.api.error;

/** Stable error categories independent of HTTP or RPC transports. */
public enum DnaRuntimeErrorCategory {
  VALIDATION,
  AUTHORIZATION,
  NOT_FOUND,
  CONFLICT,
  CAPABILITY,
  INTERNAL
}
