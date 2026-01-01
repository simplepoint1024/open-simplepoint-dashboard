package org.simplepoint.security.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;

/**
 * Permission Granted Authority.
 */
public class PermissionGrantedAuthority implements GrantedAuthority {
  @Getter
  private final String role;

  private final String authority;

  /**
   * Constructs a PermissionGrantedAuthority with the specified role and permission.
   *
   * @param role       the role associated with the permission
   * @param authority the permission string
   */
  @JsonCreator
  public PermissionGrantedAuthority(
      @JsonProperty("role") String role,
      @JsonProperty("authority") String authority
  ) {
    this.role = role;
    this.authority = authority;
  }

  @Override
  public @Nullable String getAuthority() {
    return this.authority;
  }
}
