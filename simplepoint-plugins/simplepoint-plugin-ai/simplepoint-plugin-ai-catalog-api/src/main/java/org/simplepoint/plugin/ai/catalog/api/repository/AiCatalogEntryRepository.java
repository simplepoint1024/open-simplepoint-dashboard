package org.simplepoint.plugin.ai.catalog.api.repository;

import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.catalog.api.entity.AiCatalogEntry;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogEntryStatus;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Persistence contract for cached external extension metadata.
 */
public interface AiCatalogEntryRepository extends BaseRepository<AiCatalogEntry, String> {

  /**
   * Finds a cached record by its stable registry key.
   */
  Optional<AiCatalogEntry> findByExternalKey(String externalKey);

  /**
   * Finds one non-deleted record by identifier.
   */
  Optional<AiCatalogEntry> findActiveById(String id);

  /**
   * Searches active records for one external source.
   */
  Page<AiCatalogEntry> findAllActiveBySource(
      CatalogSource source,
      CatalogEntryStatus status,
      String keyword,
      Pageable pageable
  );
}
