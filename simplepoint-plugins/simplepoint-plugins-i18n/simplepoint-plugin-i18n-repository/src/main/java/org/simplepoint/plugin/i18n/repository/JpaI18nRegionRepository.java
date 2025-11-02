package org.simplepoint.plugin.i18n.repository;

import org.simplepoint.plugin.i18n.api.entity.Region;
import org.simplepoint.plugin.i18n.api.repository.I18nRegionRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * JpaRegionRepository provides an interface for managing Region entities.
 * It extends the JpaRepository to inherit basic CRUD operations and the RegionRepository
 * to include specific methods for handling region data.
 * This interface is used to interact with the persistence layer for Region entities.
 */
@Repository
public interface JpaI18nRegionRepository extends JpaRepository<Region, String>, I18nRegionRepository {
}
