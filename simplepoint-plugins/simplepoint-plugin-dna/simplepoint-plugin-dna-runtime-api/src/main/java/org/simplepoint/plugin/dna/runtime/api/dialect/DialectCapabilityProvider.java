package org.simplepoint.plugin.dna.runtime.api.dialect;

/** Extension point implemented by version-aware database dialects. */
public interface DialectCapabilityProvider {

  /** Returns whether this provider owns the supplied product and version. */
  boolean supports(DatabaseIdentity database);

  /** Builds the complete capability descriptor for the supplied database. */
  DialectCapabilityDescriptor describe(DatabaseIdentity database);
}
