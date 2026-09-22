package org.simplepoint.core.permission;

/** A bounded, user-facing validation failure from the permission configuration domain. */
public class InvalidPermissionPolicyException extends IllegalArgumentException {
  public InvalidPermissionPolicyException(String message) { super(message); }
}
