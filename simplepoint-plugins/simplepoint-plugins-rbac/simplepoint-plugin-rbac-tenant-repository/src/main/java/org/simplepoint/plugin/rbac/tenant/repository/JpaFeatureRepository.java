package org.simplepoint.plugin.rbac.tenant.repository;

import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.Feature;
import org.simplepoint.plugin.rbac.tenant.api.repository.FeatureRepository;
import org.springframework.stereotype.Repository;

/**
 * JpaFeatureRepository provides an interface for managing Feature entities.
 * It extends the BaseRepository to inherit basic CRUD operations and the FeatureRepository
 * to include specific methods for handling feature data.
 * This interface is used to interact with the persistence layer for Feature entities.
 */
@Repository
public interface JpaFeatureRepository extends BaseRepository<Feature, String>, FeatureRepository {
}
