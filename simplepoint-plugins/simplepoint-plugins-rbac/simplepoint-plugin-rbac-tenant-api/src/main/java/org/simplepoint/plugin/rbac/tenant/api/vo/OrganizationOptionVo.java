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
}
