package org.simplepoint.core.authority;

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
  private final String id;

  private final String authority;

  @Getter
  private final String roleId;

  @Getter
  private final String roleAuthority;

  /**
   * Constructs a PermissionGrantedAuthority with the specified parameters.
   *
   * @param id            the unique identifier of the permission
   * @param authority     the authority string of the permission
   * @param roleId        the unique identifier of the associated role
   * @param roleAuthority the authority string of the associated role
   */
  @JsonCreator
  public PermissionGrantedAuthority(
      @JsonProperty("id") String id,
      @JsonProperty("authority") String authority,
      @JsonProperty("roleId") String roleId,
      @JsonProperty("roleAuthority") String roleAuthority
  ) {
    this.id = id;
    this.authority = authority;
    this.roleId = roleId;
    this.roleAuthority = roleAuthority;
  }

  @Override
  public @Nullable String getAuthority() {
    return this.authority;
  }
}
