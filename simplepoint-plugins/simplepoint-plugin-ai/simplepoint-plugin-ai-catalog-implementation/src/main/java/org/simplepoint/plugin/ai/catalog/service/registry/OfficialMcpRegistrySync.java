package org.simplepoint.plugin.ai.catalog.service.registry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.plugin.ai.catalog.api.entity.AiCatalogEntry;
import org.simplepoint.plugin.ai.catalog.api.entity.AiCatalogSyncState;
import org.simplepoint.plugin.ai.catalog.api.model.AiCatalogSyncErrorCode;
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
@Slf4j
public class OfficialMcpRegistrySync {

  private static final long ADVISORY_LOCK_ID = 0x534D43504341544CL;

  private final AiCatalogEntryRepository entryRepository;

  private final AiCatalogSyncStateRepository stateRepository;

  private final OfficialMcpRegistryClient registryClient;

  private final AiCatalogProperties properties;

  private final JdbcTemplate jdbcTemplate;

  private final ObjectMapper objectMapper;

  /**
   * Creates the synchronization coordinator.
   */
  public OfficialMcpRegistrySync(
      final AiCatalogEntryRepository entryRepository,
      final AiCatalogSyncStateRepository stateRepository,
      final OfficialMcpRegistryClient registryClient,
      final AiCatalogProperties properties,
      final JdbcTemplate jdbcTemplate,
      final ObjectMapper objectMapper
  ) {
    this.entryRepository = entryRepository;
    this.stateRepository = stateRepository;
    this.registryClient = registryClient;
    this.properties = properties;
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  /**
   * Runs one bounded full or incremental cursor pass.
   */
  @Transactional(rollbackFor = Exception.class)
  public AiCatalogSyncResult synchronize() {
    AiCatalogSyncState state = null;
    long count = 0;
    try {
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
            AiCatalogSyncErrorCode.OFFICIAL_MCP_SYNC_LOCK_BUSY
        );
      }
      state = stateRepository
          .findBySourceForUpdate(CatalogSource.OFFICIAL_MCP)
          .orElseGet(OfficialMcpRegistrySync::newState);
      final CatalogSyncMode mode = state.getSyncMode();
      Instant startedAt = Instant.now();
      state.setStatus(CatalogSyncStatus.RUNNING);
      state.setLastStartedAt(startedAt);
      state.setLastError(null);
      stateRepository.save(state);
      long previousCount = value(state.getSyncedCount());
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
      state.setLastError(AiCatalogSyncErrorCode
          .OFFICIAL_MCP_SYNC_PAGE_BUDGET_REACHED.name());
      stateRepository.save(state);
      return result(
          state,
          count,
          AiCatalogSyncErrorCode.OFFICIAL_MCP_SYNC_PAGE_BUDGET_REACHED
      );
    } catch (CatalogSynchronizationException ex) {
      log.error(
          "Official MCP Registry synchronization failed with code {}",
          ex.errorCode(),
          ex
      );
      return failed(state, count, ex.errorCode());
    } catch (RuntimeException ex) {
      log.error("Official MCP Registry synchronization failed", ex);
      return failed(
          state,
          count,
          AiCatalogSyncErrorCode.OFFICIAL_MCP_SYNC_FAILED
      );
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
    entry.setDescriptorJson(descriptorJson(external));
    entryRepository.save(entry);
  }

  private String descriptorJson(final OfficialRegistryEntry external) {
    try {
      return objectMapper.writeValueAsString(external.descriptor());
    } catch (JsonProcessingException ex) {
      throw new CatalogSynchronizationException(
          AiCatalogSyncErrorCode
              .OFFICIAL_MCP_DESCRIPTOR_SERIALIZATION_FAILED,
          ex
      );
    }
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
      final AiCatalogSyncErrorCode errorCode
  ) {
    return new AiCatalogSyncResult(
        state.getSource(),
        state.getSyncMode(),
        state.getStatus(),
        count,
        state.getCursor(),
        state.getLastCompletedAt(),
        errorCode
    );
  }

  private AiCatalogSyncResult failed(
      final AiCatalogSyncState state,
      final long count,
      final AiCatalogSyncErrorCode errorCode
  ) {
    if (state == null) {
      return new AiCatalogSyncResult(
          CatalogSource.OFFICIAL_MCP,
          CatalogSyncMode.FULL,
          CatalogSyncStatus.FAILED,
          count,
          null,
          null,
          errorCode
      );
    }
    state.setStatus(CatalogSyncStatus.FAILED);
    state.setLastError(errorCode.name());
    try {
      stateRepository.save(state);
    } catch (RuntimeException ex) {
      log.error(
          "Failed to persist official MCP Registry synchronization state",
          ex
      );
    }
    return result(state, count, errorCode);
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

  private static final class CatalogSynchronizationException
      extends RuntimeException {

    private final AiCatalogSyncErrorCode errorCode;

    CatalogSynchronizationException(
        final AiCatalogSyncErrorCode errorCode,
        final Throwable cause
    ) {
      super(errorCode.name(), cause);
      this.errorCode = errorCode;
    }

    AiCatalogSyncErrorCode errorCode() {
      return errorCode;
    }
  }
}
