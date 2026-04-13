package org.simplepoint.plugin.dna.federation.repository;

import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.dna.federation.api.entity.DataLineageNode;
import org.simplepoint.plugin.dna.federation.api.repository.DataLineageNodeRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for data lineage nodes.
 */
@Repository
public interface JpaDataLineageNodeRepository
    extends BaseRepository<DataLineageNode, String>, DataLineageNodeRepository {

  @Override
  @Query("""
      select n
      from DataLineageNode n
      where n.id = :id and n.deletedAt is null
      """)
  Optional<DataLineageNode> findActiveById(@Param("id") String id);

  @Override
  @Query("""
      select n
      from DataLineageNode n
      where n.catalogId = :catalogId
        and (:schemaName is null and n.schemaName is null or n.schemaName = :schemaName)
        and n.tableName = :tableName
        and n.deletedAt is null
      """)
  Optional<DataLineageNode> findActiveByCatalogIdAndSchemaNameAndTableName(
      @Param("catalogId") String catalogId,
      @Param("schemaName") String schemaName,
      @Param("tableName") String tableName);
}
