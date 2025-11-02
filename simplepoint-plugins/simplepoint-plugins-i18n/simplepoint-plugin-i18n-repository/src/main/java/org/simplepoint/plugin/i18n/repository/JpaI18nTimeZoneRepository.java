package org.simplepoint.plugin.i18n.repository;

import org.simplepoint.plugin.i18n.api.entity.TimeZone;
import org.simplepoint.plugin.i18n.api.repository.I18nTimeZoneRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * JpaTimeZoneRepository provides an interface for managing TimeZone entities.
 * It extends the JpaRepository to inherit basic CRUD operations and the TimeZoneRepository
 * to include specific methods for handling time zone data.
 * This interface is used to interact with the persistence layer for TimeZone entities.
 */
@Repository
public interface JpaI18nTimeZoneRepository extends JpaRepository<TimeZone, String>,
    I18nTimeZoneRepository {
  
}
