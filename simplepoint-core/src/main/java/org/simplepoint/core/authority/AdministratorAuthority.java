package org.simplepoint.core.authority;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * Administrator Granted Authority.
 */
public class AdministratorAuthority extends RoleGrantedAuthority {

  private static final String authority = "Administrator";

  @JsonCreator
  public AdministratorAuthority(
      @JsonProperty("authority") String authority
  ) {
    super(null, AdministratorAuthority.authority);
  }

  @Override
  public @Nullable String getAuthority() {
    return AdministratorAuthority.authority;
  }
}
