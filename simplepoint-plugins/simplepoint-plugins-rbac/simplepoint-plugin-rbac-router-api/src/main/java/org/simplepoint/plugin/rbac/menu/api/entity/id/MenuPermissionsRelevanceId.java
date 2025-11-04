package org.simplepoint.plugin.rbac.menu.api.entity.id;

import java.io.Serializable;
import lombok.Data;

/**
 * MenuPermissionsRelevanceId represents the composite key for the
 * MenuPermissionsRelevance entity, which defines the relationship
 * between menu authorities and permission authorities in the RBAC system.
 */
@Data
public class MenuPermissionsRelevanceId implements Serializable {

  private String menuAuthority;

  private String permissionAuthority;
}
