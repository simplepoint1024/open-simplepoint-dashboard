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

  @Getter
  private final String name;

  private final String authority;

  /**
   * Constructs a RoleGrantedAuthority with the specified parameters.
   *
   * @param id        the unique identifier of the role
   * @param authority the authority string of the role
   */
  public RoleGrantedAuthority(
      String id,
      String authority
  ) {
    this(id, null, authority);
  }

  /**
   * Constructs a role authority with its user-facing name.
   *
   * @param id        the unique identifier of the role
   * @param name      the user-facing role name
   * @param authority the authority string of the role
   */
  @JsonCreator
  public RoleGrantedAuthority(
      @JsonProperty("id") String id,
      @JsonProperty("name") String name,
      @JsonProperty("authority") String authority
  ) {
    this.id = id;
    this.name = name;
    this.authority = authority;
  }

  @Override
  public @Nullable String getAuthority() {
    return this.authority;
  }
}
