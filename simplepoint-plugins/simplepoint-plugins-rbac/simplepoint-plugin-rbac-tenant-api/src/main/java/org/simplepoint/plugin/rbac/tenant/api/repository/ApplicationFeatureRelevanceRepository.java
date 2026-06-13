package org.simplepoint.plugin.rbac.tenant.api.repository;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.simplepoint.plugin.rbac.tenant.api.entity.ApplicationFeatureRelevance;

/**
 * Repository for application feature relations.
 */
public interface ApplicationFeatureRelevanceRepository {

  /**
   * Save All.
   */
  <S extends ApplicationFeatureRelevance> List<S> saveAll(Iterable<S> entities);

  /**
   * Unauthorized.
   */
  void unauthorized(String applicationCode, Set<String> featureCodes);

  /**
   * Authorized.
   */
  Collection<String> authorized(String applicationCode);

  /**
   * Delete All By Application Codes.
   */
  void deleteAllByApplicationCodes(Collection<String> applicationCodes);

  /**
   * Delete All By Feature Codes.
   */
  void deleteAllByFeatureCodes(Collection<String> featureCodes);

  /**
   * Update Application Code.
   */
  void updateApplicationCode(String oldCode, String newCode);

  /**
   * Update Feature Code.
   */
  void updateFeatureCode(String oldCode, String newCode);
}
