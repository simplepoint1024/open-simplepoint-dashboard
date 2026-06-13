package org.simplepoint.plugin.rbac.tenant.api.vo;

import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;

/**
 * NamedTenantVo is a value object that represents a tenant with its ID, name, and type.
 * It is used to transfer tenant information in a simplified form, typically for display purposes.
 */
public record NamedTenantVo(String tenantId, String tenantName, TenantType tenantType) {
}
