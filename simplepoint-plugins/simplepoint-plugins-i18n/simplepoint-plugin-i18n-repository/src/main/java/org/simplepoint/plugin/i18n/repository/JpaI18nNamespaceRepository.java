package org.simplepoint.plugin.i18n.repository;

import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.i18n.api.entity.Namespace;
import org.simplepoint.plugin.i18n.api.repository.I18nNamespaceRepository;
import org.springframework.stereotype.Repository;

/**
 * JpaNamespaceRepository provides an interface for managing Namespace entities.
 * It extends the BaseRepository to inherit basic CRUD operations and the I18nNamespaceRepository
 * to include specific methods for handling namespace data.
 * This interface is used to interact with the persistence layer for Namespace entities.
 */
@Repository
public interface JpaI18nNamespaceRepository extends BaseRepository<Namespace, String>, I18nNamespaceRepository {
}
