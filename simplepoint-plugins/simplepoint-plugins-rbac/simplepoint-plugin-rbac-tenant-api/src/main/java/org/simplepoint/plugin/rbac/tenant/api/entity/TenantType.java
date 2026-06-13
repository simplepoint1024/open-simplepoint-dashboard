package org.simplepoint.plugin.rbac.tenant.api.entity;

/**
 * Defines the type of a tenant in the system.
 *
 * <p>PERSONAL – automatically created per-user workspace; does not support
 * features that require a full organisation tenant.</p>
 *
 * <p>ORGANIZATION – a shared, administrator-managed tenant.</p>
 */
public enum TenantType {
  PERSONAL,
  ORGANIZATION
}
