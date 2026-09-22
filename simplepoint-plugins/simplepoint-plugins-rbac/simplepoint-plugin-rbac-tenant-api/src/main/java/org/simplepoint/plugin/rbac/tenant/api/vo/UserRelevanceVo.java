package org.simplepoint.plugin.rbac.tenant.api.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import org.simplepoint.core.annotation.PermissionFields;
import org.simplepoint.core.query.QueryField;
import org.simplepoint.core.query.TableQuery;
import org.simplepoint.security.entity.User;

/**
 * Simplified user view for owner and member selection.
 *
 * @param id user identifier
 * @param name display name
 * @param email email address
 * @param phoneNumber phone number
 */
@TableQuery({@QueryField("id"), @QueryField("name"), @QueryField("email"), @QueryField("phoneNumber")})
public record UserRelevanceVo(
    @PermissionFields(resource = User.class, value = "id") @Schema(title = "i18n:users.title.id") String id,
    @PermissionFields(resource = User.class, value = {"nickname", "name", "email", "phoneNumber", "id"})
    @Schema(title = "i18n:users.title.name") String name,
    @PermissionFields(resource = User.class, value = "email") @Schema(title = "i18n:users.title.email") String email,
    @PermissionFields(resource = User.class, value = "phoneNumber")
    @Schema(title = "i18n:users.title.phoneNumber") String phoneNumber) {

  /** Explicit read projection preserves field permissions with both supported JSON converters. */
  public java.util.Map<String, Object> project() {
    var values = new java.util.LinkedHashMap<String, Object>();
    values.put("id", id);
    values.put("name", name);
    values.put("email", email);
    values.put("phoneNumber", phoneNumber);
    return org.simplepoint.core.jackson.FieldPermissionPolicy.project(UserRelevanceVo.class, values);
  }
}
