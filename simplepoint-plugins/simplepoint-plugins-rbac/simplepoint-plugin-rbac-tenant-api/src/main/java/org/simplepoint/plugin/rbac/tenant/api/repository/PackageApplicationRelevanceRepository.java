package org.simplepoint.plugin.rbac.tenant.api.repository;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.simplepoint.plugin.rbac.tenant.api.entity.PackageApplicationRelevance;

/**
 * Repository for package application relations.
 */
public interface PackageApplicationRelevanceRepository {

  <S extends PackageApplicationRelevance> List<S> saveAll(Iterable<S> entities);

  void unauthorized(String packageCode, Set<String> applicationCodes);

  Collection<String> authorized(String packageCode);

  void deleteAllByPackageCodes(Collection<String> packageCodes);

  void deleteAllByApplicationCodes(Collection<String> applicationCodes);

  void updatePackageCode(String oldCode, String newCode);

  void updateApplicationCode(String oldCode, String newCode);
}
