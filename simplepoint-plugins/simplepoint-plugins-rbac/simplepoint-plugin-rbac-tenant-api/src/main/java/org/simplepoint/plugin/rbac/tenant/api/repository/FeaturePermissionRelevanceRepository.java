package org.simplepoint.plugin.rbac.tenant.api.repository;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.simplepoint.plugin.rbac.tenant.api.entity.FeaturePermissionRelevance;

/**
 * Repository for feature permission relations.
 */
public interface FeaturePermissionRelevanceRepository {

  /**
   * Save All.
   */
  <S extends FeaturePermissionRelevance> List<S> saveAll(Iterable<S> entities);

  /**
   * Unauthorized.
   */
  void unauthorized(String featureCode, Set<String> permissionAuthority);

  /**
   * Delete All By Permission Authorities.
   */
  void deleteAllByPermissionAuthorities(Collection<String> permissionAuthorities);

  /**
   * Authorized.
   */
  Collection<String> authorized(String featureCode);

  /**
   * Delete All By Feature Codes.
   */
  void deleteAllByFeatureCodes(Collection<String> featureCodes);

  /**
   * Update Feature Code.
   */
  void updateFeatureCode(String oldCode, String newCode);

  /**
   * Update Permission Authority.
   */
  void updatePermissionAuthority(String oldAuthority, String newAuthority);

  /**
   * Find Permission Authorities By Tenant Id.
   */
  Collection<String> findPermissionAuthoritiesByTenantId(String tenantId);

  /**
   * Find Feature Codes By Permission Authorities.
   */
  Collection<String> findFeatureCodesByPermissionAuthorities(Collection<String> permissionAuthorities);

  /**
   * Returns all feature codes whose {@code publicAccess} flag is {@code true}.
   *
   * @return distinct feature codes marked as publicly accessible
   */
  Collection<String> findPublicAccessFeatureCodes();

  /**
   * Returns all permission authorities that belong to features whose {@code publicAccess} flag is {@code true}.
   *
   * @return distinct permission authorities for publicly accessible features
   */
  Collection<String> findPermissionAuthoritiesByPublicAccessFeatures();
}
