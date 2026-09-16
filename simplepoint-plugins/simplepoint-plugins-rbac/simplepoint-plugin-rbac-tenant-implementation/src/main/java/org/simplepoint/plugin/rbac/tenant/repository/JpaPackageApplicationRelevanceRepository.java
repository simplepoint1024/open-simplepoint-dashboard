package org.simplepoint.plugin.rbac.tenant.repository;

import java.util.Collection;
import java.util.Set;
import org.simplepoint.plugin.rbac.tenant.api.entity.PackageApplicationRelevance;
import org.simplepoint.plugin.rbac.tenant.api.repository.PackageApplicationRelevanceRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Jpa repository for package application relations.
 */
@Repository
public interface JpaPackageApplicationRelevanceRepository
    extends JpaRepository<PackageApplicationRelevance, String>, PackageApplicationRelevanceRepository {

  @Override
  @Modifying
  @Query("delete from PackageApplicationRelevance par where par.packageCode = ?1 and par.applicationCode in ?2")
  void unauthorized(String packageCode, Set<String> applicationCodes);

  @Override
  @Query("select distinct par.applicationCode from PackageApplicationRelevance par where par.packageCode = ?1")
  Collection<String> authorized(String packageCode);

  @Override
  @Modifying
  @Query("delete from PackageApplicationRelevance par where par.packageCode in ?1")
  void deleteAllByPackageCodes(Collection<String> packageCodes);

  @Override
  @Modifying
  @Query("delete from PackageApplicationRelevance par where par.applicationCode in ?1")
  void deleteAllByApplicationCodes(Collection<String> applicationCodes);

  @Override
  @Modifying
  @Query("update PackageApplicationRelevance par set par.packageCode = ?2 where par.packageCode = ?1")
  void updatePackageCode(String oldCode, String newCode);

  @Override
  @Modifying
  @Query("update PackageApplicationRelevance par set par.applicationCode = ?2 where par.applicationCode = ?1")
  void updateApplicationCode(String oldCode, String newCode);
}
