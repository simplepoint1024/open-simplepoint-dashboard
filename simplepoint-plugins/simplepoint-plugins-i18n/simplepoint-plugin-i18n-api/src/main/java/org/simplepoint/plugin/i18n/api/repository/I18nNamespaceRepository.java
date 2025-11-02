package org.simplepoint.plugin.i18n.api.repository;

import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.i18n.api.entity.Namespace;

/**
 * NamespaceRepository provides an interface for managing Namespace entities.
 * It extends the BaseRepository to inherit basic CRUD operations.
 * This interface is used to interact with the persistence layer for Namespace entities.
 */
public interface I18nNamespaceRepository extends BaseRepository<Namespace, String> {
}
