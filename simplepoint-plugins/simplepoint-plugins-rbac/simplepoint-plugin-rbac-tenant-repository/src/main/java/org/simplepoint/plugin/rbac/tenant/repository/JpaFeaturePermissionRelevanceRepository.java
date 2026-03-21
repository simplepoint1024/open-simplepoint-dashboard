package org.simplepoint.plugin.rbac.tenant.repository;

import java.util.Collection;
import java.util.Set;
import org.simplepoint.plugin.rbac.tenant.api.entity.FeaturePermissionRelevance;
import org.simplepoint.plugin.rbac.tenant.api.repository.FeaturePermissionRelevanceRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Jpa repository for feature permission relations.
 */
@Repository
public interface JpaFeaturePermissionRelevanceRepository
    extends JpaRepository<FeaturePermissionRelevance, String>, FeaturePermissionRelevanceRepository {

  @Override
  @Modifying
  @Query("delete from FeaturePermissionRelevance fpr where fpr.featureCode = ?1 and fpr.permissionAuthority in ?2")
  void unauthorized(String featureCode, Set<String> permissionAuthority);

  @Override
  @Query("select distinct fpr.permissionAuthority from FeaturePermissionRelevance fpr where fpr.featureCode = ?1")
  Collection<String> authorized(String featureCode);

  @Override
  @Modifying
  @Query("delete from FeaturePermissionRelevance fpr where fpr.featureCode in ?1")
  void deleteAllByFeatureCodes(Collection<String> featureCodes);

  @Override
  @Modifying
  @Query("update FeaturePermissionRelevance fpr set fpr.featureCode = ?2 where fpr.featureCode = ?1")
  void updateFeatureCode(String oldCode, String newCode);

  @Override
  @Query("""
      select distinct fpr.permissionAuthority
      from TenantPackageRelevance tpr
      join PackageApplicationRelevance par on par.packageCode = tpr.packageCode
      join ApplicationFeatureRelevance afr on afr.applicationCode = par.applicationCode
      join FeaturePermissionRelevance fpr on fpr.featureCode = afr.featureCode
      where tpr.tenantId = ?1
      """)
  Collection<String> findPermissionAuthoritiesByTenantId(String tenantId);
}
