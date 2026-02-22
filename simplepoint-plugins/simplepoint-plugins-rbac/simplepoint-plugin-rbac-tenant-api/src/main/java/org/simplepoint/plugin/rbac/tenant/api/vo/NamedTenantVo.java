package org.simplepoint.plugin.rbac.tenant.api.vo;

/**
 * NamedTenantVo is a value object that represents a tenant with its ID and name.
 * It is used to transfer tenant information in a simplified form, typically for display purposes.
 */
public record NamedTenantVo(String tenantId, String tenantName) {
}
