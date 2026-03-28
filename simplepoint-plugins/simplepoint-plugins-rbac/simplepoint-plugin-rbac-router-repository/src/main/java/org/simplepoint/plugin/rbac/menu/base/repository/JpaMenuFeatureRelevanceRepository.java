package org.simplepoint.plugin.rbac.menu.base.repository;

import java.util.Collection;
import java.util.Set;
import org.simplepoint.plugin.rbac.menu.api.entity.MenuFeatureRelevance;
import org.simplepoint.plugin.rbac.menu.api.repository.MenuFeatureRelevanceRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Jpa repository for menu feature relations.
 */
@Repository
public interface JpaMenuFeatureRelevanceRepository
    extends JpaRepository<MenuFeatureRelevance, String>, MenuFeatureRelevanceRepository {

  @Override
  @Modifying
  @Query("DELETE FROM MenuFeatureRelevance mfr WHERE mfr.menuId IN ?1")
  void deleteAllByMenuIds(Collection<String> menuIds);

  @Override
  @Modifying
  @Query("DELETE FROM MenuFeatureRelevance mfr WHERE mfr.menuId = ?1 AND mfr.featureCode IN ?2")
  void unauthorized(String menuId, Set<String> featureCodes);

  @Override
  @Query("SELECT mfr.featureCode FROM MenuFeatureRelevance mfr WHERE mfr.menuId = ?1")
  Collection<String> authorized(String menuId);

  @Override
  @Query("SELECT mfr.menuId FROM MenuFeatureRelevance mfr WHERE mfr.featureCode IN ?1")
  Collection<String> findAllMenuIdByFeatureCodes(Collection<String> featureCodes);

  @Override
  default void authorize(Collection<MenuFeatureRelevance> menuFeatureRelevances) {
    this.saveAll(menuFeatureRelevances);
  }
}
