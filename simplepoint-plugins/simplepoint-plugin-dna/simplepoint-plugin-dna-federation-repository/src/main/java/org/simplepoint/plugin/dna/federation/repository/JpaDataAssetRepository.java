package org.simplepoint.plugin.dna.federation.repository;

import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.dna.federation.api.entity.DataAsset;
import org.simplepoint.plugin.dna.federation.api.repository.DataAssetRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for data assets.
 */
@Repository
public interface JpaDataAssetRepository
    extends BaseRepository<DataAsset, String>, DataAssetRepository {

  @Override
  @Query("""
      select a
      from DataAsset a
      where a.id = :id and a.deletedAt is null
      """)
  Optional<DataAsset> findActiveById(@Param("id") String id);

  @Override
  @Query("""
      select a
      from DataAsset a
      where a.code = :code and a.deletedAt is null
      """)
  Optional<DataAsset> findActiveByCode(@Param("code") String code);
}
