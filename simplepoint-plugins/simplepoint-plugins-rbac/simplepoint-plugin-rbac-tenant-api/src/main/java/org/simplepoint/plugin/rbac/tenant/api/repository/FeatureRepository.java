package org.simplepoint.plugin.rbac.tenant.api.repository;

import java.util.Collection;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.Feature;

/**
 * FeatureRepository provides an interface for managing Feature entities.
 * It extends the BaseRepository to inherit basic CRUD operations.
 * This interface is used to interact with the persistence layer for Feature entities.
 */
public interface FeatureRepository extends BaseRepository<Feature, String> {
  /**
   * Loads features by codes.
   *
   * @param codes feature codes
   * @return matched features
   */
  Collection<Feature> findAllByCodes(Collection<String> codes);
}
