package org.simplepoint.plugin.dna.federation.api.service;

import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryTemplate;

/**
 * Service contract for federation query templates.
 */
public interface FederationQueryTemplateService extends BaseService<FederationQueryTemplate, String> {

  /**
   * Finds an active query template by id.
   *
   * @param id template id
   * @return active query template
   */
  Optional<FederationQueryTemplate> findActiveById(String id);

  /**
   * Finds an active query template by business code.
   *
   * @param code template code
   * @return active query template
   */
  Optional<FederationQueryTemplate> findActiveByCode(String code);

  /**
   * Returns all active public query templates for the SQL console template picker.
   *
   * @return public templates
   */
  List<FederationQueryTemplate> findAllActivePublic();

  /**
   * Returns the count of active public templates.
   *
   * @return count
   */
  long countActivePublic();

  /**
   * Returns the count of active private templates.
   *
   * @return count
   */
  long countActivePrivate();
}
