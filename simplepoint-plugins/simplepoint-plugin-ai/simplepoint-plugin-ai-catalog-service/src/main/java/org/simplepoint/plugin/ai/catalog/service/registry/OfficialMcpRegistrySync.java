package org.simplepoint.plugin.ai.catalog.service.registry;

import java.time.Instant;
import org.simplepoint.plugin.ai.catalog.api.entity.AiCatalogEntry;
import org.simplepoint.plugin.ai.catalog.api.entity.AiCatalogSyncState;
import org.simplepoint.plugin.ai.catalog.api.model.AiCatalogSyncResult;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogEntryStatus;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogPackageKind;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogSource;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogSyncMode;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogSyncStatus;
import org.simplepoint.plugin.ai.catalog.api.repository.AiCatalogEntryRepository;
import org.simplepoint.plugin.ai.catalog.api.repository.AiCatalogSyncStateRepository;
import org.simplepoint.plugin.ai.catalog.service.impl.AiCatalogServiceImpl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactionally advances the official Registry cursor under a database lock.
 */
@Component
public class OfficialMcpRegistrySync {

  private static final long ADVISORY_LOCK_ID = 0x534D43504341544CL;

  private final AiCatalogEntryRepository entryRepository;

  private final AiCatalogSyncStateRepository stateRepository;

  private final OfficialMcpRegistryClient registryClient;

  private final AiCatalogProperties properties;

  private final JdbcTemplate jdbcTemplate;

  /**
   * Creates the synchronization coordinator.
   */
  public OfficialMcpRegistrySync(
      final AiCatalogEntryRepository entryRepository,
      final AiCatalogSyncStateRepository stateRepository,
      final OfficialMcpRegistryClient registryClient,
      final AiCatalogProperties properties,
      final JdbcTemplate jdbcTemplate
  ) {
    this.entryRepository = entryRepository;
    this.stateRepository = stateRepository;
    this.registryClient = registryClient;
    this.properties = properties;
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Runs one bounded full or incremental cursor pass.
   */
  @Transactional(rollbackFor = Exception.class)
  public AiCatalogSyncResult synchronize() {
    Boolean locked = jdbcTemplate.queryForObject(
        "select pg_try_advisory_xact_lock(?)",
        Boolean.class,
        ADVISORY_LOCK_ID
    );
    if (!Boolean.TRUE.equals(locked)) {
      return new AiCatalogSyncResult(
          CatalogSource.OFFICIAL_MCP,
          CatalogSyncMode.INCREMENTAL,
          CatalogSyncStatus.PARTIAL,
          0,
          null,
          null,
          "Another catalog synchronization is already running"
      );
    }
    AiCatalogSyncState state = stateRepository
        .findBySourceForUpdate(CatalogSource.OFFICIAL_MCP)
        .orElseGet(OfficialMcpRegistrySync::newState);
    CatalogSyncMode mode = state.getSyncMode();
    Instant startedAt = Instant.now();
    state.setStatus(CatalogSyncStatus.RUNNING);
    state.setLastStartedAt(startedAt);
    state.setLastError(null);
    stateRepository.save(state);
    long count = 0;
    long previousCount = value(state.getSyncedCount());
    try {
      String cursor = state.getCursor();
      Instant updatedSince = mode == CatalogSyncMode.INCREMENTAL
          ? first(state.getSyncSince(), state.getLastCompletedAt()) : null;
      if (mode == CatalogSyncMode.INCREMENTAL
          && state.getCursor() == null) {
        state.setSyncSince(updatedSince);
      }
      int maximumPages = positive(properties.getMaximumPagesPerRun(), 20);
      int pageSize = positive(properties.getPageSize(), 100);
      for (int pageNumber = 0; pageNumber < maximumPages; pageNumber++) {
        OfficialRegistryPage page = registryClient.fetch(
            cursor,
            updatedSince,
            mode == CatalogSyncMode.INCREMENTAL,
            pageSize
        );
        for (OfficialRegistryEntry external : page.entries()) {
          upsert(external);
          count++;
        }
        cursor = normalize(page.nextCursor());
        state.setCursor(cursor);
        state.setSyncedCount(previousCount + count);
        stateRepository.save(state);
        if (cursor == null) {
          state.setSyncMode(CatalogSyncMode.INCREMENTAL);
          state.setSyncSince(null);
          state.setStatus(CatalogSyncStatus.SUCCEEDED);
          state.setLastCompletedAt(startedAt);
          state.setLastError(null);
          stateRepository.save(state);
          return result(state, count, null);
        }
      }
      state.setStatus(CatalogSyncStatus.PARTIAL);
      state.setLastError(
          "Synchronization page budget reached; continue from saved cursor"
      );
      stateRepository.save(state);
      return result(state, count, state.getLastError());
    } catch (RuntimeException ex) {
      state.setStatus(CatalogSyncStatus.FAILED);
      state.setLastError(truncate(ex.getMessage(), 2048));
      stateRepository.save(state);
      return result(state, count, state.getLastError());
    }
  }

  private void upsert(final OfficialRegistryEntry external) {
    String key = AiCatalogServiceImpl.externalKey(
        external.name(),
        external.version()
    );
    AiCatalogEntry entry = entryRepository.findByExternalKey(key)
        .orElseGet(AiCatalogEntry::new);
    entry.setExternalKey(key);
    entry.setSource(CatalogSource.OFFICIAL_MCP);
    entry.setKind(CatalogPackageKind.MCP_SERVER);
    entry.setRegistryName(external.name());
    entry.setVersion(external.version());
    entry.setTitle(external.title());
    entry.setDescription(external.description());
    entry.setStatus("deleted".equalsIgnoreCase(external.status())
        ? CatalogEntryStatus.DELETED : CatalogEntryStatus.ACTIVE);
    entry.setTransportType(external.transportType());
    entry.setEndpointUrl(external.endpointUrl());
    entry.setRepositoryUrl(external.repositoryUrl());
    entry.setWebsiteUrl(external.websiteUrl());
    entry.setPublishedAt(external.publishedAt());
    entry.setRegistryUpdatedAt(external.updatedAt());
    entry.setRawJson(external.rawJson());
    entryRepository.save(entry);
  }

  private static AiCatalogSyncState newState() {
    AiCatalogSyncState state = new AiCatalogSyncState();
    state.setSource(CatalogSource.OFFICIAL_MCP);
    state.setSyncMode(CatalogSyncMode.FULL);
    state.setStatus(CatalogSyncStatus.NEVER);
    state.setSyncedCount(0L);
    return state;
  }

  private static AiCatalogSyncResult result(
      final AiCatalogSyncState state,
      final long count,
      final String error
  ) {
    return new AiCatalogSyncResult(
        state.getSource(),
        state.getSyncMode(),
        state.getStatus(),
        count,
        state.getCursor(),
        state.getLastCompletedAt(),
        error
    );
  }

  private static Instant first(
      final Instant value,
      final Instant fallback
  ) {
    return value == null ? fallback : value;
  }

  private static long value(final Long value) {
    return value == null ? 0 : value;
  }

  private static int positive(final Integer value, final int fallback) {
    return value == null || value <= 0 ? fallback : value;
  }

  private static String normalize(final String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private static String truncate(final String value, final int maximum) {
    if (value == null) {
      return "Unknown synchronization failure";
    }
    return value.length() <= maximum ? value : value.substring(0, maximum);
  }
}
