package org.simplepoint.plugin.i18n.api.repository;

import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.i18n.api.entity.TimeZone;

/**
 * TimeZoneRepository provides an interface for managing TimeZone entities.
 * It extends the BaseRepository to inherit basic CRUD operations.
 * This interface is used to interact with the persistence layer for TimeZone entities.
 */
public interface TimeZoneRepository extends BaseRepository<TimeZone, String> {
}
