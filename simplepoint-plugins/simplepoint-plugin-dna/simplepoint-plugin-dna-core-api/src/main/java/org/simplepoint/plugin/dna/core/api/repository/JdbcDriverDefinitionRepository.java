package org.simplepoint.plugin.dna.core.api.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDriverDefinition;

/**
 * Repository contract for JDBC driver definitions.
 */
public interface JdbcDriverDefinitionRepository extends BaseRepository<JdbcDriverDefinition, String> {

  /**
   * Finds an active driver definition by id.
   *
   * @param id driver id
   * @return active driver definition
   */
  Optional<JdbcDriverDefinition> findActiveById(String id);

  /**
   * Finds an active driver definition by business code.
   *
   * @param code driver code
   * @return active driver definition
   */
  Optional<JdbcDriverDefinition> findActiveByCode(String code);

  /**
   * Checks whether an active driver definition already exists for the code.
   *
   * @param code driver code
   * @return true when already exists
   */
  boolean existsByCode(String code);

  /**
   * Returns all active driver definitions.
   *
   * @return active driver definitions
   */
  List<JdbcDriverDefinition> findAllActive();
}
