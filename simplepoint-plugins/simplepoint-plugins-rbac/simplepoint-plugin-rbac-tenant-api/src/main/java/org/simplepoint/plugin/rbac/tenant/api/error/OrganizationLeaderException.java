package org.simplepoint.plugin.rbac.tenant.api.error;

/** A bounded failure without exposing whether an account belongs to another tenant. */
public final class OrganizationLeaderException extends RuntimeException {
  public OrganizationLeaderException() {super("Organization leader must be an active current-tenant member");}
}
