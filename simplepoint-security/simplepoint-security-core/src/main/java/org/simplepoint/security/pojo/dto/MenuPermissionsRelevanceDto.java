package org.simplepoint.security.pojo.dto;

import java.util.Set;
import lombok.Data;

/**
 * MenuPermissionsRelevanceDto is a data transfer object that encapsulates
 * the relationship between a menu and its associated permissions.
 */
@Data
public class MenuPermissionsRelevanceDto {
  private String menuAuthority;
  private Set<String> permissionAuthorities;
}
