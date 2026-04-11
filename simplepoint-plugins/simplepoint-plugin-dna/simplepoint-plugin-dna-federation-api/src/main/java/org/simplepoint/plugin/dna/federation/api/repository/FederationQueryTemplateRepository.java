package org.simplepoint.plugin.dna.federation.api.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryTemplate;

/**
 * Repository contract for federation query templates.
 */
public interface FederationQueryTemplateRepository extends BaseRepository<FederationQueryTemplate, String> {

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
   * Finds all active public query templates.
   *
   * @return public templates
   */
  List<FederationQueryTemplate> findAllActivePublic();
}
