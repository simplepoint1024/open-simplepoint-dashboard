package org.simplepoint.plugin.rbac.tenant.api.vo;

/**
 * Lightweight organization option used by lazy organization selectors.
 */
public record OrganizationOptionVo(
    String id,
    String name,
    String code,
    String parentId,
    String type,
    String description,
    Integer sort,
    boolean enabled,
    boolean hasChildren
) {
  /** Applies the same field policy used by entity rows and tree projections. */
  public java.util.Map<String, Object> project() {
    var values = new java.util.LinkedHashMap<String, Object>();
    values.put("id", id);
    values.put("name", name);
    values.put("code", code);
    values.put("parentId", parentId);
    values.put("type", type);
    values.put("description", description);
    values.put("sort", sort);
    values.put("enabled", enabled);
    var result = org.simplepoint.core.jackson.FieldPermissionPolicy.project(
        org.simplepoint.plugin.rbac.tenant.api.entity.Organization.class, values);
    result.put("hasChildren", hasChildren);
    return result;
  }
}
