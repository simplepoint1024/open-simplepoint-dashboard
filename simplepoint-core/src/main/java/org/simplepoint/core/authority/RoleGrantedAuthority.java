package org.simplepoint.core.authority;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;

/**
 * Role Granted Authority.
 */
public class RoleGrantedAuthority implements GrantedAuthority {
  @Getter
  private final String id;
  private final String authority;

  /**
   * Constructs a RoleGrantedAuthority with the specified parameters.
   *
   * @param id        the unique identifier of the role
   * @param authority the authority string of the role
   */
  @JsonCreator
  public RoleGrantedAuthority(
      @JsonProperty("id") String id,
      @JsonProperty("authority") String authority
  ) {
    this.id = id;
    this.authority = authority;
  }

  @Override
  public @Nullable String getAuthority() {
    return this.authority;
  }
}
