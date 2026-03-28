package org.simplepoint.plugin.rbac.tenant.api.repository;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageRelevance;

/**
 * Repository for tenant package relations.
 */
public interface TenantPackageRelevanceRepository {

  <S extends TenantPackageRelevance> List<S> saveAll(Iterable<S> entities);

  void unauthorized(String tenantId, Set<String> packageCodes);

  Collection<String> authorized(String tenantId);

  void deleteAllByTenantIds(Collection<String> tenantIds);

  void deleteAllByPackageCodes(Collection<String> packageCodes);

  void updatePackageCode(String oldCode, String newCode);

  Set<String> findTenantIdsByPackageCodes(Collection<String> packageCodes);

  Set<String> findTenantIdsByApplicationCodes(Collection<String> applicationCodes);

  Set<String> findTenantIdsByFeatureCodes(Collection<String> featureCodes);

  Set<String> findFeatureCodesByTenantId(String tenantId);
}
