package org.simplepoint.plugin.dna.federation.api.service;

import java.util.Optional;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.dna.federation.api.entity.FederationSchema;

/**
 * Service contract for federation schemas.
 */
public interface FederationSchemaService extends BaseService<FederationSchema, String> {

  /**
   * Finds an active schema by id.
   *
   * @param id schema id
   * @return active schema
   */
  Optional<FederationSchema> findActiveById(String id);

  /**
   * Finds an active schema by business code.
   *
   * @param code schema code
   * @return active schema
   */
  Optional<FederationSchema> findActiveByCode(String code);
}
