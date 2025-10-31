package org.simplepoint.plugin.i18n.api.repository;

import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.i18n.api.entity.Countries;

/**
 * CountriesRepository provides an interface for managing Countries entities.
 * It extends the BaseRepository to inherit basic CRUD operations.
 * This interface is used to interact with the persistence layer for Countries entities.
 */
public interface I18nCountriesRepository extends BaseRepository<Countries, String> {
}
