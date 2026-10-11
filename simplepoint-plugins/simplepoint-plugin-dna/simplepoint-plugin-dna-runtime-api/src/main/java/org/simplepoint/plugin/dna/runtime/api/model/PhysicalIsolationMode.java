package org.simplepoint.plugin.dna.runtime.api.model;

/** Isolation policy used by the server-owned physical binding. */
public enum PhysicalIsolationMode {
  SHARED_DATABASE,
  DEDICATED_SCHEMA,
  DEDICATED_DATA_SOURCE
}
