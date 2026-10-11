package org.simplepoint.plugin.dna.runtime.api.service;

/** Generates an immutable physical table name from a platform model identifier. */
public interface PhysicalTableNamingStrategy {

  /** Returns a portable immutable table identifier. */
  String tableName(String physicalModelId);
}
