package org.simplepoint.security.entity.id;

import java.io.Serializable;
import lombok.Data;

/**
 * Composite primary key class for ResourcesPermissionsRelevance entity.
 */
@Data
public class ResourcesPermissionsRelevanceId implements Serializable {

  private String permissionAuthority;

  private String resourceAuthority;
}
