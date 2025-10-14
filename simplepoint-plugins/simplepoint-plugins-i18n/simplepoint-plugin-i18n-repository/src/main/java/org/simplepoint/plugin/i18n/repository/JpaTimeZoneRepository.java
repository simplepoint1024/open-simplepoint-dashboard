package org.simplepoint.plugin.i18n.repository;

import org.simplepoint.plugin.i18n.api.entity.TimeZone;
import org.simplepoint.plugin.i18n.api.repository.TimeZoneRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * JpaTimeZoneRepository provides an interface for managing TimeZone entities.
 * It extends the JpaRepository to inherit basic CRUD operations and the TimeZoneRepository
 * to include specific methods for handling time zone data.
 * This interface is used to interact with the persistence layer for TimeZone entities.
 */
@Repository
public interface JpaTimeZoneRepository extends JpaRepository<TimeZone, String>,
    TimeZoneRepository {
  
}
