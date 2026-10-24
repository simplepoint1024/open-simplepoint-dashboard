package org.simplepoint.plugin.rbac.tenant.api.model;

/** Platform allocation request. A stable allocation ID identifies one purchase across retries. */
public record TenantPackageAssignment(String tenantId, String packageCode, String allocationId) {}
