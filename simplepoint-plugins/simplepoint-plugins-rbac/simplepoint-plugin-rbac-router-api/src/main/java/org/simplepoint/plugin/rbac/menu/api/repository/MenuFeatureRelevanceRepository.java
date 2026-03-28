package org.simplepoint.plugin.rbac.menu.api.repository;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.simplepoint.plugin.rbac.menu.api.entity.MenuFeatureRelevance;

/**
 * Repository for menu feature relations.
 */
public interface MenuFeatureRelevanceRepository {

  <S extends MenuFeatureRelevance> List<S> saveAll(Iterable<S> entities);

  void deleteAllByMenuIds(Collection<String> menuIds);

  void unauthorized(String menuId, Set<String> featureCodes);

  Collection<String> authorized(String menuId);

  Collection<String> findAllMenuIdByFeatureCodes(Collection<String> featureCodes);

  void authorize(Collection<MenuFeatureRelevance> menuFeatureRelevances);
}
