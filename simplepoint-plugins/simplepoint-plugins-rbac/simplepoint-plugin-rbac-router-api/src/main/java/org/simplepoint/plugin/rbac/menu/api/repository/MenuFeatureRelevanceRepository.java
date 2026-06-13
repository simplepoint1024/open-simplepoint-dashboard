package org.simplepoint.plugin.rbac.menu.api.repository;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.simplepoint.plugin.rbac.menu.api.entity.MenuFeatureRelevance;

/**
 * Repository for menu feature relations.
 */
public interface MenuFeatureRelevanceRepository {

  /**
   * Save All.
   */
  <S extends MenuFeatureRelevance> List<S> saveAll(Iterable<S> entities);

  /**
   * Delete All By Menu Ids.
   */
  void deleteAllByMenuIds(Collection<String> menuIds);

  /**
   * Unauthorized.
   */
  void unauthorized(String menuId, Set<String> featureCodes);

  /**
   * Authorized.
   */
  Collection<String> authorized(String menuId);

  /**
   * Find All Menu Id By Feature Codes.
   */
  Collection<String> findAllMenuIdByFeatureCodes(Collection<String> featureCodes);

  /**
   * Authorize.
   */
  void authorize(Collection<MenuFeatureRelevance> menuFeatureRelevances);
}
