package org.simplepoint.plugin.ai.catalog.repository;

import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.catalog.api.entity.AiCatalogEntry;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogEntryStatus;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogSource;
import org.simplepoint.plugin.ai.catalog.api.repository.AiCatalogEntryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA external catalog repository.
 */
@Repository
public interface JpaAiCatalogEntryRepository
    extends BaseRepository<AiCatalogEntry, String>, AiCatalogEntryRepository {

  @Override
  @Query("""
      select entry from AiCatalogEntry entry
      where entry.externalKey = :externalKey
        and entry.deletedAt is null
      """)
  Optional<AiCatalogEntry> findByExternalKey(
      @Param("externalKey") String externalKey
  );

  @Override
  @Query("""
      select entry from AiCatalogEntry entry
      where entry.id = :id and entry.deletedAt is null
      """)
  Optional<AiCatalogEntry> findActiveById(@Param("id") String id);

  @Override
  @Query(
      value = """
      select entry from AiCatalogEntry entry
      where entry.source = :source
        and entry.status = :status
        and entry.deletedAt is null
        and (
          :keyword = ''
          or lower(entry.registryName) like :keyword
          or lower(coalesce(entry.title, '')) like :keyword
          or lower(coalesce(entry.description, '')) like :keyword
        )
      order by entry.registryUpdatedAt desc, entry.registryName asc
      """,
      countQuery = """
      select count(entry) from AiCatalogEntry entry
      where entry.source = :source
        and entry.status = :status
        and entry.deletedAt is null
        and (
          :keyword = ''
          or lower(entry.registryName) like :keyword
          or lower(coalesce(entry.title, '')) like :keyword
          or lower(coalesce(entry.description, '')) like :keyword
        )
      """
  )
  Page<AiCatalogEntry> findAllActiveBySource(
      @Param("source") CatalogSource source,
      @Param("status") CatalogEntryStatus status,
      @Param("keyword") String keyword,
      Pageable pageable
  );
}
