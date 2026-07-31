package org.simplepoint.plugin.ai.catalog.api.repository;

import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.catalog.api.entity.AiCatalogSyncState;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogSource;

/**
 * Persistence contract for durable external catalog cursors.
 */
public interface AiCatalogSyncStateRepository
    extends BaseRepository<AiCatalogSyncState, String> {

  /**
   * Finds the durable synchronization state for one source.
   */
  Optional<AiCatalogSyncState> findBySource(CatalogSource source);

  /**
   * Locks the durable synchronization state for one source.
   */
  Optional<AiCatalogSyncState> findBySourceForUpdate(CatalogSource source);
}
