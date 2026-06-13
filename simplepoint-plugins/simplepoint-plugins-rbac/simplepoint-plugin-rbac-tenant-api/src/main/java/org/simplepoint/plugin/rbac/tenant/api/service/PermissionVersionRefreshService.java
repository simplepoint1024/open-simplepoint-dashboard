package org.simplepoint.plugin.rbac.tenant.api.service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import org.simplepoint.plugin.rbac.core.api.repository.RolePermissionsRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.FeaturePermissionRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Centralized permission-version refresh helper for RBAC mutation flows.
 */
@Service
public class PermissionVersionRefreshService {

  private final TenantRepository tenantRepository;
  private final TenantPackageRelevanceRepository tenantPackageRelevanceRepository;
  private final FeaturePermissionRelevanceRepository featurePermissionRelevanceRepository;
  private final RolePermissionsRelevanceRepository rolePermissionsRelevanceRepository;

  /**
   * Permission Version Refresh Service.
   */
  public PermissionVersionRefreshService(
      @Autowired(required = false) TenantRepository tenantRepository,
      @Autowired(required = false) TenantPackageRelevanceRepository tenantPackageRelevanceRepository,
      @Autowired(required = false) FeaturePermissionRelevanceRepository featurePermissionRelevanceRepository,
      @Autowired(required = false) RolePermissionsRelevanceRepository rolePermissionsRelevanceRepository
  ) {
    this.tenantRepository = tenantRepository;
    this.tenantPackageRelevanceRepository = tenantPackageRelevanceRepository;
    this.featurePermissionRelevanceRepository = featurePermissionRelevanceRepository;
    this.rolePermissionsRelevanceRepository = rolePermissionsRelevanceRepository;
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
    tenantRepository.increasePermissionVersion(normalizedTenantIds);
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
   * Refresh By Feature Codes.
   */
  public void refreshByFeatureCodes(Collection<String> featureCodes) {
    if (tenantPackageRelevanceRepository == null) {
      return;
    }
    refreshTenants(tenantPackageRelevanceRepository.findTenantIdsByFeatureCodes(normalizeCodes(featureCodes)));
  }

  /**
   * Find Affected Tenant Ids By Permission Authorities.
   */
  public Set<String> findAffectedTenantIdsByPermissionAuthorities(Collection<String> authorities) {
    Set<String> normalizedAuthorities = normalizeCodes(authorities);
    if (normalizedAuthorities.isEmpty()) {
      return Set.of();
    }

    Set<String> affectedTenantIds = new LinkedHashSet<>();
    if (rolePermissionsRelevanceRepository != null) {
      affectedTenantIds.addAll(rolePermissionsRelevanceRepository.findTenantIdsByPermissionAuthorities(normalizedAuthorities));
    }
    if (featurePermissionRelevanceRepository != null) {
      Collection<String> featureCodes =
          featurePermissionRelevanceRepository.findFeatureCodesByPermissionAuthorities(normalizedAuthorities);
      if (tenantPackageRelevanceRepository != null && featureCodes != null && !featureCodes.isEmpty()) {
        affectedTenantIds.addAll(tenantPackageRelevanceRepository.findTenantIdsByFeatureCodes(featureCodes));
      }
      Collection<String> publicAuthorities = featurePermissionRelevanceRepository.findPermissionAuthoritiesByPublicAccessFeatures();
      if (tenantRepository != null
          && publicAuthorities != null
          && normalizedAuthorities.stream().anyMatch(publicAuthorities::contains)) {
        affectedTenantIds.addAll(tenantRepository.findAllIds());
      }
    }
    return affectedTenantIds;
  }

  /**
   * Refresh By Permission Authorities.
   */
  public void refreshByPermissionAuthorities(Collection<String> authorities) {
    refreshTenants(findAffectedTenantIdsByPermissionAuthorities(authorities));
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
