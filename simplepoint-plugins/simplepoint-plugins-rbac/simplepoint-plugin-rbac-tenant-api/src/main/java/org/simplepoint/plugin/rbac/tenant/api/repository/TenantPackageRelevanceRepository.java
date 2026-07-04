package org.simplepoint.plugin.rbac.tenant.api.repository;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageRelevance;

/**
 * Repository for tenant package relations.
 */
public interface TenantPackageRelevanceRepository {

  /**
   * Save All.
   */
  <S extends TenantPackageRelevance> List<S> saveAll(Iterable<S> entities);

  /**
   * Unauthorized.
   */
  void unauthorized(String tenantId, Set<String> packageCodes);

  /**
   * Authorized.
   */
  Collection<String> authorized(String tenantId);

  /**
   * Delete All By Tenant Ids.
   */
  void deleteAllByTenantIds(Collection<String> tenantIds);

  /**
   * Delete All By Package Codes.
   */
  void deleteAllByPackageCodes(Collection<String> packageCodes);

  /**
   * Update Package Code.
   */
  void updatePackageCode(String oldCode, String newCode);

  /**
   * Find Tenant Ids By Package Codes.
   */
  Set<String> findTenantIdsByPackageCodes(Collection<String> packageCodes);

  /**
   * Find Tenant Ids By Application Codes.
   */
  Set<String> findTenantIdsByApplicationCodes(Collection<String> applicationCodes);

  /**
   * Find Tenant Ids By Resource Codes.
   */
  Set<String> findTenantIdsByResourceCodes(Collection<String> resourceCodes);

  /**
   * Find Resource Codes By Tenant Id.
   */
  Set<String> findResourceCodesByTenantId(String tenantId);

  /**
   * Find Application Codes By Tenant Id.
   */
  Set<String> findApplicationCodesByTenantId(String tenantId);
}
