package org.simplepoint.plugin.dna.runtime.api.dialect;

/** Selects the single capability provider for a database identity. */
public interface DialectCapabilityResolver {

  /** Resolves exactly one version-aware provider or rejects the database as unknown. */
  DialectCapabilityDescriptor resolve(DatabaseIdentity database);
}
