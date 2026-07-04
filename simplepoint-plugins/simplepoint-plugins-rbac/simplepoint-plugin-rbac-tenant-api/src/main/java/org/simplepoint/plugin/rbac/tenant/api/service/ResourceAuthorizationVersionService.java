package org.simplepoint.plugin.rbac.tenant.api.service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import org.simplepoint.plugin.rbac.core.api.repository.RoleResourceGrantRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Centralized authorization-version refresh helper for RBAC resource mutation flows.
 */
@Service
public class ResourceAuthorizationVersionService {

  private final TenantRepository tenantRepository;
  private final TenantPackageRelevanceRepository tenantPackageRelevanceRepository;
  private final RoleResourceGrantRepository roleResourceGrantRepository;

  public ResourceAuthorizationVersionService(
      @Autowired(required = false) TenantRepository tenantRepository,
      @Autowired(required = false) TenantPackageRelevanceRepository tenantPackageRelevanceRepository,
      @Autowired(required = false) RoleResourceGrantRepository roleResourceGrantRepository
  ) {
    this.tenantRepository = tenantRepository;
    this.tenantPackageRelevanceRepository = tenantPackageRelevanceRepository;
    this.roleResourceGrantRepository = roleResourceGrantRepository;
  }

  /**
   * Refresh Tenant.
   */
  public void refreshTenant(String tenantId) {
    if (tenantId == null || tenantId.isBlank()) {
      return;
    }
    refreshTenants(Set.of(tenantId));
  }

  /**
   * Refresh Tenants.
   */
  public void refreshTenants(Collection<String> tenantIds) {
    if (tenantRepository == null || tenantIds == null || tenantIds.isEmpty()) {
      return;
    }
    Set<String> normalizedTenantIds = tenantIds.stream()
        .filter(tenantId -> tenantId != null && !tenantId.isBlank())
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    if (normalizedTenantIds.isEmpty()) {
      return;
    }
    tenantRepository.increaseAuthorizationVersion(normalizedTenantIds);
  }

  /**
   * Refresh By Package Codes.
   */
  public void refreshByPackageCodes(Collection<String> packageCodes) {
    if (tenantPackageRelevanceRepository == null) {
      return;
    }
    refreshTenants(tenantPackageRelevanceRepository.findTenantIdsByPackageCodes(normalizeCodes(packageCodes)));
  }

  /**
   * Refresh By Application Codes.
   */
  public void refreshByApplicationCodes(Collection<String> applicationCodes) {
    if (tenantPackageRelevanceRepository == null) {
      return;
    }
    refreshTenants(tenantPackageRelevanceRepository.findTenantIdsByApplicationCodes(normalizeCodes(applicationCodes)));
  }

  /**
   * Find Affected Tenant Ids By Resource Codes.
   */
  public Set<String> findAffectedTenantIdsByResourceCodes(Collection<String> resourceCodes) {
    Set<String> normalizedResourceCodes = normalizeCodes(resourceCodes);
    if (normalizedResourceCodes.isEmpty()) {
      return Set.of();
    }

    Set<String> affectedTenantIds = new LinkedHashSet<>();
    if (roleResourceGrantRepository != null) {
      affectedTenantIds.addAll(roleResourceGrantRepository.findTenantIdsByResourceCodes(normalizedResourceCodes));
    }
    if (tenantPackageRelevanceRepository != null) {
      affectedTenantIds.addAll(tenantPackageRelevanceRepository.findTenantIdsByResourceCodes(normalizedResourceCodes));
    }
    return affectedTenantIds;
  }

  public void refreshByResourceCodes(Collection<String> resourceCodes) {
    refreshTenants(findAffectedTenantIdsByResourceCodes(resourceCodes));
  }

  private static Set<String> normalizeCodes(Collection<String> codes) {
    if (codes == null || codes.isEmpty()) {
      return Set.of();
    }
    return codes.stream()
        .filter(code -> code != null && !code.isBlank())
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
  }
}
