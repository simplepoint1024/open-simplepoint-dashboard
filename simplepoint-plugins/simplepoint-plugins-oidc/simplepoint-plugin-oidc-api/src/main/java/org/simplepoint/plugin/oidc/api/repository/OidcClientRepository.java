package org.simplepoint.plugin.oidc.api.repository;

import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.oidc.api.entity.Client;

/**
 * ClientRepository provides an interface for managing Client entities.
 * It extends the BaseRepository to inherit basic CRUD operations.
 * This interface is used to interact with the persistence layer for Client entities.
 */
public interface OidcClientRepository extends BaseRepository<Client, String> {
}
