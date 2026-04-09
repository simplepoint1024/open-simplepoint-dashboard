package org.simplepoint.plugin.dna.federation.api.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.dna.federation.api.entity.FederationView;

/**
 * Repository contract for federation views.
 */
public interface FederationViewRepository extends BaseRepository<FederationView, String> {

  /**
   * Finds an active view by id.
   *
   * @param id view id
   * @return active view
   */
  Optional<FederationView> findActiveById(String id);

  /**
   * Finds an active view by business code.
   *
   * @param code view code
   * @return active view
   */
  Optional<FederationView> findActiveByCode(String code);

  /**
   * Finds all active views whose parent schema ids are included in the supplied set.
   *
   * @param schemaIds schema ids
   * @return active views
   */
  List<FederationView> findAllActiveBySchemaIds(Collection<String> schemaIds);

  /**
   * Checks whether an active view already exists for the code.
   *
   * @param code view code
   * @return true when already exists
   */
  boolean existsByCode(String code);
}
