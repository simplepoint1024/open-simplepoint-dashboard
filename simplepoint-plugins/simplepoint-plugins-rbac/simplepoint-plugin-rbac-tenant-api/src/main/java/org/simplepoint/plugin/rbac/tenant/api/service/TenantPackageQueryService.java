package org.simplepoint.plugin.rbac.tenant.api.service;

import java.util.Map;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantPackageInstanceView;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantPackageResourceView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Current-owner-only package queries; tenant identity is never supplied by the browser. */
public interface TenantPackageQueryService {
  /** Returns the platform query schema after checking current ownership. */
  Map<String, Object> schema();

  /** Filters and sorts the current tenant's package instances before server pagination. */
  Page<TenantPackageInstanceView> query(Map<String, String> filters, Pageable pageable);

  /** Loads one instance without exposing persistence entities or foreign tenant existence. */
  TenantPackageInstanceView get(String instanceId);

  /** Pages purchase content and current balances entirely from tenant-owned records. */
  Page<TenantPackageResourceView> resources(String instanceId, Pageable pageable);
}
