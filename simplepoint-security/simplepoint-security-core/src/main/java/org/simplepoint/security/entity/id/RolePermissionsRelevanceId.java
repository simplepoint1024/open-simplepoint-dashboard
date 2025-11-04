package org.simplepoint.security.entity.id;

import java.io.Serializable;
import lombok.Data;

/**
 * Composite primary key class for RolePermissionsRelevance entity.
 */
@Data
public class RolePermissionsRelevanceId implements Serializable {
  private String roleAuthority;
  private String permissionAuthority;
}
