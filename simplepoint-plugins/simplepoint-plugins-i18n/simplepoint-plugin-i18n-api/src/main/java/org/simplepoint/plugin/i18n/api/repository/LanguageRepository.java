package org.simplepoint.plugin.i18n.api.repository;

import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.i18n.api.entity.Language;

/**
 * LanguageRepository provides an interface for managing Language entities.
 * It extends the BaseRepository to inherit basic CRUD operations.
 * This interface is used to interact with the persistence layer for Language entities.
 */
public interface LanguageRepository extends BaseRepository<Language, String> {
}
