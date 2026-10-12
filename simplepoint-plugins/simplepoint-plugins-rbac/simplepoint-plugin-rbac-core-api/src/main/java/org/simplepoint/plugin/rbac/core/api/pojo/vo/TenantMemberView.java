package org.simplepoint.plugin.rbac.core.api.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import org.simplepoint.core.annotation.PermissionFields;
import org.simplepoint.core.query.QueryField;
import org.simplepoint.core.query.TableQuery;
import org.simplepoint.security.entity.User;

/** Tenant membership read model; computed fields inherit their source permissions. */
@TableQuery({@QueryField("userId"), @QueryField("name"), @QueryField("email"),
    @QueryField("orgId"), @QueryField("orgName"), @QueryField("enabled"), @QueryField("userType")})
@org.simplepoint.core.permission.PermissionModel(page="users.view", owner="userId", department="orgId")
public record TenantMemberView(
    @PermissionFields(resource = User.class, value = "id")
    @Schema(title = "用户标识")
    String userId,
    @PermissionFields(resource = User.class, value = {"name", "familyName", "middleName", "givenName", "email"})
    @Schema(title = "i18n:platform-accounts.name")
    String name,
    @PermissionFields(resource = User.class, value = "email")
    @Schema(title = "i18n:platform-accounts.email")
    String email,
    @PermissionFields(resourceName = "org.simplepoint.plugin.rbac.tenant.api.entity.TenantUserRelevance", value = "orgId")
    @Schema(title = "组织标识")
    String orgId,
    @PermissionFields(resourceName = "org.simplepoint.plugin.rbac.tenant.api.entity.Organization", value = "name")
    @Schema(title = "i18n:platform-accounts.memberOrg")
    String orgName,
    @PermissionFields(resourceName = "org.simplepoint.plugin.rbac.tenant.api.entity.TenantUserRelevance", value = "enabled")
    @Schema(title = "i18n:platform-accounts.memberEnabled")
    boolean enabled,
    @PermissionFields(resourceName = "org.simplepoint.plugin.rbac.tenant.api.entity.TenantUserRelevance", value = "userType")
    @Schema(title = "i18n:tenant-members.userType")
    String userType,
    Long revision
) {
  /** Explicit projection preserves source-field policies across JSON converter versions. */
  public java.util.Map<String, Object> project() {
    var values = new java.util.LinkedHashMap<String, Object>();
    values.put("userId", userId);
    values.put("name", name);
    values.put("email", email);
    values.put("orgId", orgId);
    values.put("orgName", orgName);
    values.put("enabled", enabled);
    values.put("userType", userType);
    values.put("revision", revision);
    return org.simplepoint.core.jackson.FieldPermissionPolicy.project(TenantMemberView.class, values);
  }
}
