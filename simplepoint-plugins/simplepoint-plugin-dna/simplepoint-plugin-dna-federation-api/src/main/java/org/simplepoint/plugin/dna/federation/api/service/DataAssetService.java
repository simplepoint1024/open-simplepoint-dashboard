package org.simplepoint.plugin.dna.federation.api.service;

import java.util.Optional;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.dna.federation.api.entity.DataAsset;

/**
 * Service contract for data assets.
 */
public interface DataAssetService extends BaseService<DataAsset, String> {

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

  /**
   * Counts all active data assets.
   *
   * @return active asset count
   */
  long countActive();
}
