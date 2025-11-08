package org.simplepoint.security.entity.id;

import lombok.Data;

/**
 * Composite key class for UserRoleRelevance entity.
 */
@Data
public class UserRoleRelevanceId {
  private String username;

  private String authority;
}
