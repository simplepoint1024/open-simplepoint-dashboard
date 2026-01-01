package org.simplepoint.security.entity;

import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;

/**
 * Permission Granted Authority.
 */
public class PermissionGrantedAuthority implements GrantedAuthority {
  @Getter
  private final String role;

  private final String permission;

  /**
   * Constructs a PermissionGrantedAuthority with the specified role and permission.
   *
   * @param role       the role associated with the permission
   * @param permission the permission string
   */
  public PermissionGrantedAuthority(String role, String permission) {
    this.role = role;
    this.permission = permission;
  }

  @Override
  public @Nullable String getAuthority() {
    return this.permission;
  }
}
