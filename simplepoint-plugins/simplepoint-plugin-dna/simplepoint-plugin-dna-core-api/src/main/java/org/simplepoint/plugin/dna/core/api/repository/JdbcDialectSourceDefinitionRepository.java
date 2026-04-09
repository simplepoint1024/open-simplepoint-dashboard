package org.simplepoint.plugin.dna.core.api.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDialectSourceDefinition;

/**
 * Repository contract for JDBC dialect source definitions.
 */
public interface JdbcDialectSourceDefinitionRepository extends BaseRepository<JdbcDialectSourceDefinition, String> {

  /**
   * Finds an active dialect source definition by id.
   *
   * @param id source id
   * @return active source definition
   */
  Optional<JdbcDialectSourceDefinition> findActiveById(String id);

  /**
   * Returns all active dialect source definitions.
   *
   * @return active source definitions
   */
  List<JdbcDialectSourceDefinition> findAllActive();
}
