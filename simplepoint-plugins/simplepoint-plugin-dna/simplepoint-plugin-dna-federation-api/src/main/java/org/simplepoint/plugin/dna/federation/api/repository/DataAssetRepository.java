package org.simplepoint.plugin.dna.federation.api.repository;

import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.dna.federation.api.entity.DataAsset;

/**
 * Repository contract for data assets.
 */
public interface DataAssetRepository extends BaseRepository<DataAsset, String> {

  /**
   * Finds an active data asset by id.
   *
   * @param id asset id
   * @return active data asset
   */
  Optional<DataAsset> findActiveById(String id);

  /**
   * Finds an active data asset by business code.
   *
   * @param code asset code
   * @return active data asset
   */
  Optional<DataAsset> findActiveByCode(String code);
}
