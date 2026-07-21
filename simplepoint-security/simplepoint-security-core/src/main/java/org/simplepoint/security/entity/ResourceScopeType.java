package org.simplepoint.security.entity;

/**
 * Security boundary in which a resource may be exposed and granted.
 *
 * <p>The values are discrete audiences rather than an ordinal hierarchy. A resource that is
 * intentionally shared by more than one workspace declares every supported scope explicitly.</p>
 */
public enum ResourceScopeType {
  /** Infrastructure and security-sensitive operations reserved for the system administrator. */
  SYSTEM,

  /** Platform control-plane operations delegated to platform operators. */
  PLATFORM,

  /** Organization-tenant workspace capabilities. */
  TENANT,

  /** Personal workspace capabilities owned by an individual user. */
  PERSONAL
}
