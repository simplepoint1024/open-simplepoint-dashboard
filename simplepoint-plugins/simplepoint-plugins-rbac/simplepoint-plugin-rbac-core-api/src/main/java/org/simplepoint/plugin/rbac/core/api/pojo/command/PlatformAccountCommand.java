package org.simplepoint.plugin.rbac.core.api.pojo.command;

import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.simplepoint.security.entity.PlatformRole;

/** Explicit write allowlist; no entity binding and no generated credential-bearing toString. */
@Getter @Setter
public class PlatformAccountCommand {
  private String name;
  private String email;
  private String initialPassword;
  private Boolean enabled;
  private Boolean superAdmin;
  private Set<PlatformRole> roles;
  private Long revision;
  private String reason;
  private String confirmationPassword;
  private String confirmationCode;
}
