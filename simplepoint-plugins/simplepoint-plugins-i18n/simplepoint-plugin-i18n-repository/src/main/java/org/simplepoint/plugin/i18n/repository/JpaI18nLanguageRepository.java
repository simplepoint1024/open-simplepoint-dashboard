package org.simplepoint.plugin.i18n.repository;

import java.util.Collection;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.i18n.api.entity.Language;
import org.simplepoint.plugin.i18n.api.repository.I18nLanguageRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * JpaI18nLanguageRepository provides an interface for managing Language entities.
 * It extends the BaseRepository to inherit basic CRUD operations.
 * This interface is used to interact with the persistence layer for Language entities.
 */
@Repository
public interface JpaI18nLanguageRepository extends BaseRepository<Language, String>, I18nLanguageRepository {
  @Override
  @Query("SELECT l FROM Language l where l.enabled = true")
  Collection<Language> mapping();
}
