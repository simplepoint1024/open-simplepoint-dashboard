package org.simplepoint.plugin.i18n.repository;

import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.i18n.api.entity.Language;
import org.simplepoint.plugin.i18n.api.repository.LanguageRepository;
import org.springframework.stereotype.Repository;

/**
 * JpaLanguageRepository provides a JPA implementation of the LanguageRepository interface.
 * It extends BaseRepository to inherit basic CRUD operations for Language entities.
 * This repository is used to interact with the persistence layer for Language entities.
 */
@Repository
public interface JpaLanguageRepository extends BaseRepository<Language, String>, LanguageRepository {
}
