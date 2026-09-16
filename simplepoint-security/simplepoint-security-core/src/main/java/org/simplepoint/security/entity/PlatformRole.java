package org.simplepoint.security.entity;

import java.util.Set;

/** Platform-only capabilities. These roles never imply the super-administrator bypass. */
public enum PlatformRole {
  PLATFORM_ADMIN(Set.of("platform.accounts.view", "platform.accounts.create", "platform.accounts.edit", "platform.audit.view")),
  ACCOUNT_ADMIN(Set.of("platform.accounts.view", "platform.accounts.create", "platform.accounts.edit")),
  AUDITOR(Set.of("platform.audit.view"));

  private final Set<String> permissions;
  PlatformRole(Set<String> permissions) { this.permissions = permissions; }
  public Set<String> permissions() { return permissions; }
}
