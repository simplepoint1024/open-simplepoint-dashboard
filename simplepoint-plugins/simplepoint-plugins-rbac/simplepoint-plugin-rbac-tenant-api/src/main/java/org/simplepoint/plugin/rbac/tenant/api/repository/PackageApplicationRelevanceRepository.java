package org.simplepoint.plugin.rbac.tenant.api.repository;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.simplepoint.plugin.rbac.tenant.api.entity.PackageApplicationRelevance;

/**
 * Repository for package application relations.
 */
public interface PackageApplicationRelevanceRepository {

  /**
   * Save All.
   */
  <S extends PackageApplicationRelevance> List<S> saveAll(Iterable<S> entities);

  /**
   * Unauthorized.
   */
  void unauthorized(String packageCode, Set<String> applicationCodes);

  /**
   * Authorized.
   */
  Collection<String> authorized(String packageCode);

  /**
   * Delete All By Package Codes.
   */
  void deleteAllByPackageCodes(Collection<String> packageCodes);

  /**
   * Delete All By Application Codes.
   */
  void deleteAllByApplicationCodes(Collection<String> applicationCodes);

  /**
   * Update Package Code.
   */
  void updatePackageCode(String oldCode, String newCode);

  /**
   * Update Application Code.
   */
  void updateApplicationCode(String oldCode, String newCode);
}
