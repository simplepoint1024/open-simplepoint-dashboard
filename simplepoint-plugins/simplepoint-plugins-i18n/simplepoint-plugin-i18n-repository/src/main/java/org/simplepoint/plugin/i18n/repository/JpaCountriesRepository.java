package org.simplepoint.plugin.i18n.repository;

import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.i18n.api.entity.Countries;
import org.simplepoint.plugin.i18n.api.repository.CountriesRepository;
import org.springframework.stereotype.Repository;

/**
 * JpaCountriesRepository provides an interface for managing Countries entities.
 * It extends the BaseRepository to inherit basic CRUD operations and the CountriesRepository
 * to include specific methods for handling countries data.
 * This interface is used to interact with the persistence layer for Countries entities.
 */
@Repository
public interface JpaCountriesRepository extends BaseRepository<Countries, String>,
    CountriesRepository {
}
