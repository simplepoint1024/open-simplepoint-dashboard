package org.simplepoint.plugin.rbac.core.api.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serializable;
import lombok.Data;
import org.simplepoint.core.annotation.PermissionFields;
import org.simplepoint.core.query.QueryField;
import org.simplepoint.core.query.TableQuery;
import org.simplepoint.security.entity.Role;

/**
 * RoleRelevanceVo is a value object that encapsulates
 * the details of a role.
 */
@Data
@TableQuery({@QueryField("id"), @QueryField("name"), @QueryField("authority"), @QueryField("description")})
public class RoleRelevanceVo implements Serializable {
  @PermissionFields(resource = Role.class, value = "roleName")
  @Schema(title = "i18n:roles.title.roleName")
  private String name;
  @PermissionFields(resource = Role.class, value = "description")
  @Schema(title = "i18n:roles.title.description")
  private String description;
  @PermissionFields(resource = Role.class, value = "authority")
  @Schema(title = "i18n:roles.title.authority")
  private String authority;
  @PermissionFields(resource = Role.class, value = "id")
  @Schema(title = "i18n:roles.title.id")
  private String id;

  /** Projects only authorized presentation fields for both JSON converter versions. */
  public java.util.Map<String, Object> project() {
    var values = new java.util.LinkedHashMap<String, Object>();
    values.put("id", id);
    values.put("name", name);
    values.put("authority", authority);
    values.put("description", description);
    return org.simplepoint.core.jackson.FieldPermissionPolicy.project(RoleRelevanceVo.class, values);
  }

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
