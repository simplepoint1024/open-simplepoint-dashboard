package org.simplepoint.plugin.rbac.core.api.pojo.dto;

import java.io.Serializable;
import java.util.Set;
import lombok.Data;

/**
 * RoleSelectDto is a data transfer object that encapsulates information
 * about a user's role selection, including the username and associated role authorities.
 */
@Data
public class RoleSelectDto implements Serializable {
  private String username;
  private Set<String> roleAuthorities;
}
