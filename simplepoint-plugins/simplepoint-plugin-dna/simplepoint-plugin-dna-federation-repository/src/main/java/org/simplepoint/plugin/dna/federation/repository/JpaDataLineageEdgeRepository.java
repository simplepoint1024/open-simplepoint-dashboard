package org.simplepoint.plugin.dna.federation.repository;

import java.util.List;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.dna.federation.api.entity.DataLineageEdge;
import org.simplepoint.plugin.dna.federation.api.repository.DataLineageEdgeRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for data lineage edges.
 */
@Repository
public interface JpaDataLineageEdgeRepository
    extends BaseRepository<DataLineageEdge, String>, DataLineageEdgeRepository {

  @Override
  @Query("""
      select e
      from DataLineageEdge e
      where e.sourceNodeId = :sourceNodeId and e.deletedAt is null
      """)
  List<DataLineageEdge> findActiveBySourceNodeId(@Param("sourceNodeId") String sourceNodeId);

  @Override
  @Query("""
      select e
      from DataLineageEdge e
      where e.targetNodeId = :targetNodeId and e.deletedAt is null
      """)
  List<DataLineageEdge> findActiveByTargetNodeId(@Param("targetNodeId") String targetNodeId);
}
