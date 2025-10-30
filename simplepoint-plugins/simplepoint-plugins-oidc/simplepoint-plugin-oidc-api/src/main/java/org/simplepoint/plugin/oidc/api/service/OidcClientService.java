package org.simplepoint.plugin.oidc.api.service;

import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.oidc.api.entity.Client;

/**
 * ClientService provides an interface for managing Client entities.
 * It extends the BaseService to inherit basic CRUD operations.
 * This interface is used to define business logic related to Client entities.
 */
public interface OidcClientService extends BaseService<Client, String> {
}
