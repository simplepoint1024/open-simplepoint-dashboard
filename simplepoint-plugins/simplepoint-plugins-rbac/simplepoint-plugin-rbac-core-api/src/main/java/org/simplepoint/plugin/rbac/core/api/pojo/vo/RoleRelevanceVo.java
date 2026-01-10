package org.simplepoint.plugin.rbac.core.api.pojo.vo;

import java.io.Serializable;
import lombok.Data;

/**
 * RoleRelevanceVo is a value object that encapsulates
 * the details of a role.
 */
@Data
public class RoleRelevanceVo implements Serializable {
  private String name;
  private String description;
  private String authority;
  private String id;

  /**
   * Constructs a new RoleSelectDto with the specified name, id, and description.
   *
   * @param name        the name of the role
   * @param id          the id string of the role
   * @param authority   the authority string of the role
   * @param description the description of the role
   */
  public RoleRelevanceVo(
      String id,
      String name,
      String authority,
      String description
  ) {
    this.name = name;
    this.id = id;
    this.authority = authority;
    this.description = description;
  }

  /**
   * Default constructor for RoleRelevanceVo.
   */
  public RoleRelevanceVo() {
  }
}
