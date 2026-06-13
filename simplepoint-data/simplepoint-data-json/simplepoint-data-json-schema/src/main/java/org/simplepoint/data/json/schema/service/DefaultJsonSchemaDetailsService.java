package org.simplepoint.data.json.schema.service;

import jakarta.persistence.EntityManager;
import org.simplepoint.api.security.service.JsonSchemaDetailsService;
import org.simplepoint.remoting.RemoteProvider;
import org.springframework.stereotype.Service;

/**
 * Default implementation of JsonSchemaDetailsService.
 */
@Service
@RemoteProvider
public class DefaultJsonSchemaDetailsService implements JsonSchemaDetailsService {

  private final EntityManager entityManager;

  /**
   * Constructor for DefaultJsonSchemaDetailsService.
   *
   * @param entityManager the EntityManager to be used by this service
   */
  public DefaultJsonSchemaDetailsService(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

}
