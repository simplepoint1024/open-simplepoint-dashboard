package org.simplepoint.plugin.ai.catalog.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.catalog.api.entity.AiCatalogSyncState;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogSource;
import org.simplepoint.plugin.ai.catalog.api.repository.AiCatalogSyncStateRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA catalog synchronization state repository.
 */
@Repository
public interface JpaAiCatalogSyncStateRepository
    extends BaseRepository<AiCatalogSyncState, String>,
    AiCatalogSyncStateRepository {

  @Override
  @Query("""
      select state from AiCatalogSyncState state
      where state.source = :source and state.deletedAt is null
      """)
  Optional<AiCatalogSyncState> findBySource(@Param("source") CatalogSource source);

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select state from AiCatalogSyncState state
      where state.source = :source and state.deletedAt is null
      """)
  Optional<AiCatalogSyncState> findBySourceForUpdate(
      @Param("source") CatalogSource source
  );
}
