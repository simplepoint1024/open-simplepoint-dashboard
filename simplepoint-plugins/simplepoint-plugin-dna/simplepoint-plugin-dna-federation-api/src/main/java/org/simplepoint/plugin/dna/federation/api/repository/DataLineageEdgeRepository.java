package org.simplepoint.plugin.dna.federation.api.repository;

import java.util.List;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.dna.federation.api.entity.DataLineageEdge;

/**
 * Repository contract for data lineage edges.
 */
public interface DataLineageEdgeRepository extends BaseRepository<DataLineageEdge, String> {

  /**
   * Finds active edges originating from a source node.
   *
   * @param sourceNodeId source node id
   * @return downstream edges
   */
  List<DataLineageEdge> findActiveBySourceNodeId(String sourceNodeId);

  /**
   * Finds active edges terminating at a target node.
   *
   * @param targetNodeId target node id
   * @return upstream edges
   */
  List<DataLineageEdge> findActiveByTargetNodeId(String targetNodeId);
}
