package org.simplepoint.plugin.oidc.api.service;

import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.oidc.api.entity.Client;
import org.simplepoint.plugin.oidc.api.pojo.dto.OidcClientConfigurationDto;

/**
 * ClientService provides an interface for managing Client entities.
 * It extends the BaseService to inherit basic CRUD operations.
 * This interface is used to define business logic related to Client entities.
 */
public interface OidcClientService extends BaseService<Client, String> {

  /**
   * Loads a structured configuration view for a client.
   *
   * @param id client primary key
   * @return structured configuration
   */
  OidcClientConfigurationDto configuration(String id);

  /**
   * Creates a client from structured configuration.
   *
   * @param dto structured configuration
   * @return created client
   */
  Client createConfiguration(OidcClientConfigurationDto dto);

  /**
   * Updates a client from structured configuration.
   *
   * @param id client primary key
   * @param dto structured configuration
   * @return updated client
   */
  Client updateConfiguration(String id, OidcClientConfigurationDto dto);
}
