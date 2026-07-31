package org.simplepoint.plugin.ai.catalog.service.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.ai.catalog.api.entity.AiCatalogEntry;
import org.simplepoint.plugin.ai.catalog.api.entity.AiCatalogSyncState;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogEntryStatus;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogSyncMode;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogSyncStatus;
import org.simplepoint.plugin.ai.catalog.api.repository.AiCatalogEntryRepository;
import org.simplepoint.plugin.ai.catalog.api.repository.AiCatalogSyncStateRepository;
import org.springframework.jdbc.core.JdbcTemplate;

class OfficialMcpRegistrySyncTest {

  @Test
  void advancesFullCursorAndPersistsDeletedMetadata() {
    AiCatalogEntryRepository entries = mock(AiCatalogEntryRepository.class);
    AiCatalogSyncStateRepository states =
        mock(AiCatalogSyncStateRepository.class);
    OfficialMcpRegistryClient client = mock(OfficialMcpRegistryClient.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(
        eq("select pg_try_advisory_xact_lock(?)"),
        eq(Boolean.class),
        anyLong()
    )).thenReturn(true);
    when(states.findBySourceForUpdate(any())).thenReturn(Optional.empty());
    when(states.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(entries.findByExternalKey(any())).thenReturn(Optional.empty());
    when(entries.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    AtomicInteger calls = new AtomicInteger();
    when(client.fetch(any(), any(), eq(false), eq(100)))
        .thenAnswer(invocation -> calls.getAndIncrement() == 0
            ? new OfficialRegistryPage(
                List.of(entry("alpha", "active")),
                "cursor-2"
            )
            : new OfficialRegistryPage(
                List.of(entry("beta", "deleted")),
                null
            ));

    OfficialMcpRegistrySync sync = new OfficialMcpRegistrySync(
        entries,
        states,
        client,
        new AiCatalogProperties(),
        jdbc
    );

    var result = sync.synchronize();

    assertThat(result.status()).isEqualTo(CatalogSyncStatus.SUCCEEDED);
    assertThat(result.mode()).isEqualTo(CatalogSyncMode.INCREMENTAL);
    assertThat(result.syncedCount()).isEqualTo(2);
    assertThat(result.nextCursor()).isNull();
    ArgumentCaptor<AiCatalogEntry> entryCaptor =
        ArgumentCaptor.forClass(AiCatalogEntry.class);
    verify(entries, org.mockito.Mockito.times(2)).save(entryCaptor.capture());
    assertThat(entryCaptor.getAllValues())
        .extracting(AiCatalogEntry::getStatus)
        .containsExactly(CatalogEntryStatus.ACTIVE, CatalogEntryStatus.DELETED);
    ArgumentCaptor<AiCatalogSyncState> stateCaptor =
        ArgumentCaptor.forClass(AiCatalogSyncState.class);
    verify(states, org.mockito.Mockito.atLeastOnce()).save(stateCaptor.capture());
    AiCatalogSyncState finalState = stateCaptor.getValue();
    assertThat(finalState.getStatus()).isEqualTo(CatalogSyncStatus.SUCCEEDED);
    assertThat(finalState.getSyncedCount()).isEqualTo(2);
  }

  private static OfficialRegistryEntry entry(
      final String name,
      final String status
  ) {
    return new OfficialRegistryEntry(
        name,
        "1.0.0",
        name,
        "description",
        status,
        "streamable-http",
        "https://example.com/" + name,
        null,
        null,
        Instant.parse("2026-07-01T00:00:00Z"),
        Instant.parse("2026-07-02T00:00:00Z"),
        "{}"
    );
  }
}
