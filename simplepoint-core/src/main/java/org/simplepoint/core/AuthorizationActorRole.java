package org.simplepoint.core;

/**
 * Standard role of the current actor within the active authorization scope.
 */
public enum AuthorizationActorRole {
  PLATFORM_ADMIN,
  TENANT_OWNER,
  TENANT_ADMIN,
  TENANT_MEMBER,
  PERSONAL_OWNER,
  PERSONAL_MEMBER
}
