package org.simplepoint.core;

/**
 * Standard resource namespaces used by platform, tenant, and personal scopes.
 */
public final class AuthorizationResourceNamespaces {

  public static final String PLATFORM_PREFIX = "platform.";

  public static final String TENANT_PREFIX = "tenant.";

  public static final String SELF_PREFIX = "self.";

  public static final String PLATFORM_ADMIN = PLATFORM_PREFIX + "admin";

  public static final String TENANT_ADMIN = TENANT_PREFIX + "admin";

  private AuthorizationResourceNamespaces() {
  }
}
