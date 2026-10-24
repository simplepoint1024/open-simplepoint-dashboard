package org.simplepoint.plugin.rbac.tenant.service.impl;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.springframework.stereotype.Service;

/**
 * Tenant-scoped invalidation; owning mutations supply affected tenants from their current catalog.
 */
@Service
public class ResourceAuthorizationVersionServiceImpl implements ResourceAuthorizationVersionService {
  private final TenantRepository tenantRepository;

  /**
   * Uses the required identity-owned repository; missing persistence cannot silently skip
   * invalidation.
   */
  public ResourceAuthorizationVersionServiceImpl(TenantRepository tenantRepository) {
    this.tenantRepository = Objects.requireNonNull(tenantRepository);
  }

  /** Invalidates one explicitly affected tenant. */
  @Override
  public void refreshTenant(String tenantId) {
    if (tenantId != null && !tenantId.isBlank()) {
      refreshTenants(Set.of(tenantId));
    }
  }

  /** Invalidates a deduplicated tenant cohort without consulting obsolete package relations. */
  @Override
  public void refreshTenants(Collection<String> tenantIds) {
    if (tenantIds == null || tenantIds.isEmpty()) {
      return;
    }
    var normalized = new LinkedHashSet<String>();
    for (String id : tenantIds) {
      if (id != null && !id.isBlank()) {
        normalized.add(id);
      }
    }
    var ids = new java.util.ArrayList<>(normalized);
    for (int offset = 0; offset < ids.size(); offset += 100) {
      tenantRepository.increaseAuthorizationVersion(
          ids.subList(offset, Math.min(offset + 100, ids.size())));
    }
  }
}
