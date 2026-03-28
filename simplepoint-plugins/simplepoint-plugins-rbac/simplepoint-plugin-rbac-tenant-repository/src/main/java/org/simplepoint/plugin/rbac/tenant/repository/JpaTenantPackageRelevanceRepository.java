package org.simplepoint.plugin.rbac.tenant.repository;

import java.util.Collection;
import java.util.Set;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageRelevance;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Jpa repository for tenant package relations.
 */
@Repository
public interface JpaTenantPackageRelevanceRepository
    extends JpaRepository<TenantPackageRelevance, String>, TenantPackageRelevanceRepository {

  @Override
  @Modifying
  @Query("delete from TenantPackageRelevance tpr where tpr.tenantId = ?1 and tpr.packageCode in ?2")
  void unauthorized(String tenantId, Set<String> packageCodes);

  @Override
  @Query("select distinct tpr.packageCode from TenantPackageRelevance tpr where tpr.tenantId = ?1")
  Collection<String> authorized(String tenantId);

  @Override
  @Modifying
  @Query("delete from TenantPackageRelevance tpr where tpr.tenantId in ?1")
  void deleteAllByTenantIds(Collection<String> tenantIds);

  @Override
  @Modifying
  @Query("delete from TenantPackageRelevance tpr where tpr.packageCode in ?1")
  void deleteAllByPackageCodes(Collection<String> packageCodes);

  @Override
  @Modifying
  @Query("update TenantPackageRelevance tpr set tpr.packageCode = ?2 where tpr.packageCode = ?1")
  void updatePackageCode(String oldCode, String newCode);

  @Override
  @Query("select distinct tpr.tenantId from TenantPackageRelevance tpr where tpr.packageCode in ?1")
  Set<String> findTenantIdsByPackageCodes(Collection<String> packageCodes);

  @Override
  @Query("""
      select distinct tpr.tenantId
      from TenantPackageRelevance tpr
      join PackageApplicationRelevance par on par.packageCode = tpr.packageCode
      where par.applicationCode in ?1
      """)
  Set<String> findTenantIdsByApplicationCodes(Collection<String> applicationCodes);

  @Override
  @Query("""
      select distinct tpr.tenantId
      from TenantPackageRelevance tpr
      join PackageApplicationRelevance par on par.packageCode = tpr.packageCode
      join ApplicationFeatureRelevance afr on afr.applicationCode = par.applicationCode
      where afr.featureCode in ?1
      """)
  Set<String> findTenantIdsByFeatureCodes(Collection<String> featureCodes);

  @Override
  @Query("""
      select distinct afr.featureCode
      from TenantPackageRelevance tpr
      join PackageApplicationRelevance par on par.packageCode = tpr.packageCode
      join ApplicationFeatureRelevance afr on afr.applicationCode = par.applicationCode
      where tpr.tenantId = ?1
      """)
  Set<String> findFeatureCodesByTenantId(String tenantId);
}
