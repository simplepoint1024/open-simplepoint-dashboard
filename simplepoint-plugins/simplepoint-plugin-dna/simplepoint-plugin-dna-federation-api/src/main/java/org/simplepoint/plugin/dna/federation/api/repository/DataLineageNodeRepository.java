package org.simplepoint.plugin.dna.federation.api.repository;

import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.dna.federation.api.entity.DataLineageNode;

/**
 * Repository contract for data lineage nodes.
 */
public interface DataLineageNodeRepository extends BaseRepository<DataLineageNode, String> {

  /**
   * Finds an active lineage node by id.
   *
   * @param id node id
   * @return active node
   */
  Optional<DataLineageNode> findActiveById(String id);
}
