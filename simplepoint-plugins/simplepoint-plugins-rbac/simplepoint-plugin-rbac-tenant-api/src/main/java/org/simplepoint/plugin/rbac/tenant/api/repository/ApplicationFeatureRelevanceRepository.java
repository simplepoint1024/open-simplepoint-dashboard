package org.simplepoint.plugin.rbac.tenant.api.repository;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.simplepoint.plugin.rbac.tenant.api.entity.ApplicationFeatureRelevance;

/**
 * Repository for application feature relations.
 */
public interface ApplicationFeatureRelevanceRepository {

  <S extends ApplicationFeatureRelevance> List<S> saveAll(Iterable<S> entities);

  void unauthorized(String applicationCode, Set<String> featureCodes);

  Collection<String> authorized(String applicationCode);

  void deleteAllByApplicationCodes(Collection<String> applicationCodes);

  void deleteAllByFeatureCodes(Collection<String> featureCodes);

  void updateApplicationCode(String oldCode, String newCode);

  void updateFeatureCode(String oldCode, String newCode);
}
