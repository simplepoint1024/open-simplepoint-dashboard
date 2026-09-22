package org.simplepoint.plugin.rbac.tenant.api.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import org.simplepoint.core.annotation.PermissionFields;
import org.simplepoint.core.query.QueryField;
import org.simplepoint.core.query.TableQuery;
import org.simplepoint.plugin.rbac.tenant.api.entity.Organization;

/** Authorized scalar projection for the form's draft organization picker. */
@TableQuery({@QueryField("id"), @QueryField("name"), @QueryField("code"), @QueryField("type"),
    @QueryField("description"), @QueryField("enabled")})
public record OrganizationSelectionRow(
    @PermissionFields(resource = Organization.class, value = "id") @Schema(title = "i18n:organizations.title.id") String id,
    @PermissionFields(resource = Organization.class, value = "name") @Schema(title = "i18n:organizations.title.name") String name,
    @PermissionFields(resource = Organization.class, value = "code") @Schema(title = "i18n:organizations.title.code") String code,
    @PermissionFields(resource = Organization.class, value = "type") @Schema(title = "i18n:organizations.title.type") String type,
    @PermissionFields(resource = Organization.class, value = "description")
    @Schema(title = "i18n:organizations.title.description") String description,
    @PermissionFields(resource = Organization.class, value = "enabled") @Schema(title = "i18n:organizations.title.enabled") Boolean enabled) {

  /** Explicit projection avoids depending on a particular Jackson converter. */
  public java.util.Map<String, Object> project() {
    var fields = new java.util.LinkedHashMap<String, Object>();
    fields.put("id", id);
    fields.put("name", name);
    fields.put("code", code);
    fields.put("type", type);
    fields.put("description", description);
    fields.put("enabled", enabled);
    return org.simplepoint.core.jackson.FieldPermissionPolicy.project(OrganizationSelectionRow.class, fields);
  }
}
