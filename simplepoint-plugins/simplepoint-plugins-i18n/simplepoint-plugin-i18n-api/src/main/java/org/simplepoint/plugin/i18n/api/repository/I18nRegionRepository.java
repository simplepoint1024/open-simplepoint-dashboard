package org.simplepoint.plugin.i18n.api.repository;

import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.i18n.api.entity.Region;

/**
 * RegionRepository provides an interface for managing Region entities.
 * It extends the BaseRepository to inherit basic CRUD operations.
 * This interface is used to interact with the persistence layer for Region entities.
 */
public interface I18nRegionRepository extends BaseRepository<Region, String> {
}
