package org.simplepoint.plugin.rbac.tenant.repository;

import java.util.Collection;
import java.util.Set;
import org.simplepoint.plugin.rbac.tenant.api.entity.ApplicationFeatureRelevance;
import org.simplepoint.plugin.rbac.tenant.api.repository.ApplicationFeatureRelevanceRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Jpa repository for application feature relations.
 */
@Repository
public interface JpaApplicationFeatureRelevanceRepository
    extends JpaRepository<ApplicationFeatureRelevance, String>, ApplicationFeatureRelevanceRepository {

  @Override
  @Modifying
  @Query("delete from ApplicationFeatureRelevance afr where afr.applicationCode = ?1 and afr.featureCode in ?2")
  void unauthorized(String applicationCode, Set<String> featureCodes);

  @Override
  @Query("select distinct afr.featureCode from ApplicationFeatureRelevance afr where afr.applicationCode = ?1")
  Collection<String> authorized(String applicationCode);

  @Override
  @Modifying
  @Query("delete from ApplicationFeatureRelevance afr where afr.applicationCode in ?1")
  void deleteAllByApplicationCodes(Collection<String> applicationCodes);

  @Override
  @Modifying
  @Query("delete from ApplicationFeatureRelevance afr where afr.featureCode in ?1")
  void deleteAllByFeatureCodes(Collection<String> featureCodes);

  @Override
  @Modifying
  @Query("update ApplicationFeatureRelevance afr set afr.applicationCode = ?2 where afr.applicationCode = ?1")
  void updateApplicationCode(String oldCode, String newCode);

  @Override
  @Modifying
  @Query("update ApplicationFeatureRelevance afr set afr.featureCode = ?2 where afr.featureCode = ?1")
  void updateFeatureCode(String oldCode, String newCode);
}
