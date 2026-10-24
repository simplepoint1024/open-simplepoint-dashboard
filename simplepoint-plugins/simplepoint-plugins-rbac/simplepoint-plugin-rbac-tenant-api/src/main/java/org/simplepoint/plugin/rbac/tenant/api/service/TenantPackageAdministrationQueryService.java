package org.simplepoint.plugin.rbac.tenant.api.service;

import java.util.Map;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantPackageInstanceView;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantPackageResourceView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Explicit platform administrator read boundary for a selected tenant's purchased instances. */
public interface TenantPackageAdministrationQueryService {
  /** Checks the platform actor and tenant before returning administration actions. */
  Map<String, Object> schema(String tenantId);

  /** Filters and pages instances within exactly one selected tenant. */
  Page<TenantPackageInstanceView> query(
      String tenantId, Map<String, String> filters, Pageable pageable);

  /** Reads an instance without permitting cross-tenant identity substitution. */
  TenantPackageInstanceView get(String tenantId, String instanceId);

  /** Purchase details and current time balances; no live platform resource joins. */
  Page<TenantPackageResourceView> resources(String tenantId, String instanceId, Pageable pageable);
}
