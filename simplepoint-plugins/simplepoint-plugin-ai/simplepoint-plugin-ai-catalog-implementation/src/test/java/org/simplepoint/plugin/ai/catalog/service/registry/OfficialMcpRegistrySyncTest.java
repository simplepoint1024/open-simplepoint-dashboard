package org.simplepoint.plugin.ai.catalog.service.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.ai.catalog.api.entity.AiCatalogEntry;
import org.simplepoint.plugin.ai.catalog.api.entity.AiCatalogSyncState;
import org.simplepoint.plugin.ai.catalog.api.model.AiCatalogSyncErrorCode;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogEntryStatus;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogSyncMode;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogSyncStatus;
import org.simplepoint.plugin.ai.catalog.api.model.McpServerDescriptor;
import org.simplepoint.plugin.ai.catalog.api.repository.AiCatalogEntryRepository;
import org.simplepoint.plugin.ai.catalog.api.repository.AiCatalogSyncStateRepository;
import org.springframework.jdbc.core.JdbcTemplate;

class OfficialMcpRegistrySyncTest {

  private AiCatalogEntryRepository entries;

  private AiCatalogSyncStateRepository states;

  private OfficialMcpRegistryClient client;

  private JdbcTemplate jdbc;

  private AiCatalogProperties properties;

  private ObjectMapper objectMapper;

  private OfficialMcpRegistrySync sync;

  @BeforeEach
  void setUp() {
    entries = mock(AiCatalogEntryRepository.class);
    states = mock(AiCatalogSyncStateRepository.class);
    client = mock(OfficialMcpRegistryClient.class);
    jdbc = mock(JdbcTemplate.class);
    properties = new AiCatalogProperties();
    objectMapper = new ObjectMapper();
    when(jdbc.queryForObject(
        eq("select pg_try_advisory_xact_lock(?)"),
        eq(Boolean.class),
        anyLong()
    )).thenReturn(true);
    when(states.findBySourceForUpdate(any())).thenReturn(Optional.empty());
    when(states.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(entries.findByExternalKey(any())).thenReturn(Optional.empty());
    when(entries.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    rebuildSync();
  }

  @Test
  void advancesFullCursorAndPersistsDeletedMetadata() {
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

    var result = sync.synchronize();

    assertThat(result.status()).isEqualTo(CatalogSyncStatus.SUCCEEDED);
    assertThat(result.mode()).isEqualTo(CatalogSyncMode.INCREMENTAL);
    assertThat(result.syncedCount()).isEqualTo(2);
    assertThat(result.nextCursor()).isNull();
    assertThat(result.errorCode()).isNull();
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
    assertThat(finalState.getLastError()).isNull();
  }

  @Test
  void returnsStableCodeWhenAnotherSynchronizerOwnsTheLock() {
    when(jdbc.queryForObject(
        eq("select pg_try_advisory_xact_lock(?)"),
        eq(Boolean.class),
        anyLong()
    )).thenReturn(false);

    var result = sync.synchronize();

    assertThat(result.status()).isEqualTo(CatalogSyncStatus.PARTIAL);
    assertThat(result.errorCode()).isEqualTo(
        AiCatalogSyncErrorCode.OFFICIAL_MCP_SYNC_LOCK_BUSY
    );
    verify(states, never()).save(any());
    verify(client, never()).fetch(any(), any(), anyBoolean(), anyInt());
  }

  @Test
  void infrastructureFailureBeforeStateLoadReturnsOnlyGenericStableCode() {
    String privateProse = "database host and account details";
    when(jdbc.queryForObject(
        eq("select pg_try_advisory_xact_lock(?)"),
        eq(Boolean.class),
        anyLong()
    )).thenThrow(new IllegalStateException(privateProse));

    var result = sync.synchronize();

    assertThat(result.status()).isEqualTo(CatalogSyncStatus.FAILED);
    assertThat(result.errorCode()).isEqualTo(
        AiCatalogSyncErrorCode.OFFICIAL_MCP_SYNC_FAILED
    );
    assertThat(result.errorCode().name()).doesNotContain(privateProse);
    verify(states, never()).save(any());
  }

  @Test
  void persistsOnlyPageBudgetCodeWhenCursorRemains() {
    properties.setMaximumPagesPerRun(1);
    rebuildSync();
    when(client.fetch(any(), any(), eq(false), eq(100)))
        .thenReturn(new OfficialRegistryPage(
            List.of(entry("alpha", "active")),
            "next-page"
        ));

    var result = sync.synchronize();

    assertThat(result.status()).isEqualTo(CatalogSyncStatus.PARTIAL);
    assertThat(result.errorCode()).isEqualTo(
        AiCatalogSyncErrorCode.OFFICIAL_MCP_SYNC_PAGE_BUDGET_REACHED
    );
    assertThat(savedState().getLastError()).isEqualTo(
        "OFFICIAL_MCP_SYNC_PAGE_BUDGET_REACHED"
    );
  }

  @Test
  void descriptorSerializationFailureReturnsAndPersistsOnlyStableCode()
      throws JsonProcessingException {
    objectMapper = mock(ObjectMapper.class);
    when(objectMapper.writeValueAsString(any()))
        .thenThrow(new JsonProcessingException(
            "descriptor contained environment-specific prose"
        ) {
        });
    rebuildSync();
    when(client.fetch(any(), any(), eq(false), eq(100)))
        .thenReturn(new OfficialRegistryPage(
            List.of(entry("alpha", "active")),
            null
        ));

    var result = sync.synchronize();

    assertThat(result.status()).isEqualTo(CatalogSyncStatus.FAILED);
    assertThat(result.errorCode()).isEqualTo(
        AiCatalogSyncErrorCode
            .OFFICIAL_MCP_DESCRIPTOR_SERIALIZATION_FAILED
    );
    assertThat(savedState().getLastError()).isEqualTo(
        "OFFICIAL_MCP_DESCRIPTOR_SERIALIZATION_FAILED"
    ).doesNotContain("descriptor contained");
    verify(entries, never()).save(any());
  }

  @Test
  void unknownFailureReturnsAndPersistsOnlyGenericStableCode() {
    when(client.fetch(any(), any(), eq(false), eq(100)))
        .thenThrow(new IllegalStateException(
            "upstream returned environment-specific prose"
        ));

    var result = sync.synchronize();

    assertThat(result.status()).isEqualTo(CatalogSyncStatus.FAILED);
    assertThat(result.errorCode()).isEqualTo(
        AiCatalogSyncErrorCode.OFFICIAL_MCP_SYNC_FAILED
    );
    assertThat(savedState().getLastError())
        .isEqualTo("OFFICIAL_MCP_SYNC_FAILED")
        .doesNotContain("upstream returned");
  }

  private AiCatalogSyncState savedState() {
    ArgumentCaptor<AiCatalogSyncState> captor =
        ArgumentCaptor.forClass(AiCatalogSyncState.class);
    verify(states, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
    return captor.getValue();
  }

  private void rebuildSync() {
    sync = new OfficialMcpRegistrySync(
        entries,
        states,
        client,
        properties,
        jdbc,
        objectMapper
    );
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
        new McpServerDescriptor(
            null,
            name,
            name,
            "description",
            "1.0.0",
            null,
            null,
            List.of(),
            List.of(new McpServerDescriptor.Transport(
                "streamable-http",
                "https://example.com/" + name,
                List.of(),
                java.util.Map.of()
            )),
            "{}"
        ),
        "{}"
    );
  }
}
