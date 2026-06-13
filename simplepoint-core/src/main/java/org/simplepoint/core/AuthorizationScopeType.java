package org.simplepoint.core;

/**
 * Standard runtime scope for the current authorization context.
 *
 * <p>This separates platform administration, organization tenant workspaces,
 * and personal workspaces without changing the existing RBAC permission model.</p>
 */
public enum AuthorizationScopeType {
  PLATFORM,
  TENANT,
  PERSONAL
}
