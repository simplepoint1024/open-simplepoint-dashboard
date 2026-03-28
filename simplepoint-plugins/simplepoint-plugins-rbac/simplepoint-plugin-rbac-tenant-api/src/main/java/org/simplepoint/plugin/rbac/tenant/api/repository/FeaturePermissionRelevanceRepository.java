package org.simplepoint.plugin.rbac.tenant.api.repository;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.simplepoint.plugin.rbac.tenant.api.entity.FeaturePermissionRelevance;

/**
 * Repository for feature permission relations.
 */
public interface FeaturePermissionRelevanceRepository {

  <S extends FeaturePermissionRelevance> List<S> saveAll(Iterable<S> entities);

  void unauthorized(String featureCode, Set<String> permissionAuthority);

  Collection<String> authorized(String featureCode);

  void deleteAllByFeatureCodes(Collection<String> featureCodes);

  void updateFeatureCode(String oldCode, String newCode);

  Collection<String> findPermissionAuthoritiesByTenantId(String tenantId);

  Collection<String> findFeatureCodesByPermissionAuthorities(Collection<String> permissionAuthorities);
}
