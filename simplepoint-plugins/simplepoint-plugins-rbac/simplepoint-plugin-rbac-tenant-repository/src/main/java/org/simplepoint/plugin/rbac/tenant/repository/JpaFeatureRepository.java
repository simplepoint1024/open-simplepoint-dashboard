package org.simplepoint.plugin.rbac.tenant.repository;

import java.util.Collection;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.Feature;
import org.simplepoint.plugin.rbac.tenant.api.repository.FeatureRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JpaFeatureRepository provides an interface for managing Feature entities.
 * It extends the BaseRepository to inherit basic CRUD operations and the FeatureRepository
 * to include specific methods for handling feature data.
 * This interface is used to interact with the persistence layer for Feature entities.
 */
@Repository
public interface JpaFeatureRepository extends BaseRepository<Feature, String>, FeatureRepository {
  @Override
  @Query("""
	  select f
	  from Feature f
	  where f.code in :codes
	  order by f.sort asc, f.name asc
	  """)
  Collection<Feature> findAllByCodes(@Param("codes") Collection<String> codes);
}
