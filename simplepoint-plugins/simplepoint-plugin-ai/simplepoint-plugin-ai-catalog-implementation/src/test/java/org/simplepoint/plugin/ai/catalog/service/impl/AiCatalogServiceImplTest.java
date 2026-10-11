package org.simplepoint.plugin.ai.catalog.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.ai.catalog.api.entity.AiCatalogEntry;
import org.simplepoint.plugin.ai.catalog.api.model.AiCatalogRuntimeImportRequest;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogEntryStatus;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogPackageKind;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogSource;
import org.simplepoint.plugin.ai.catalog.api.model.McpServerDescriptor;
import org.simplepoint.plugin.ai.catalog.api.repository.AiCatalogEntryRepository;
import org.simplepoint.plugin.ai.catalog.api.repository.AiCatalogSyncStateRepository;
import org.simplepoint.plugin.ai.catalog.service.registry.AiCatalogProperties;
import org.simplepoint.plugin.ai.catalog.service.registry.OfficialMcpRegistrySync;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpServerDefinitionService;
import org.simplepoint.plugin.ai.mcp.api.model.McpCatalogRegistration;
import org.simplepoint.plugin.ai.mcp.api.model.McpServerStatus;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileImportRequest;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeMcpProfileService;

class AiCatalogServiceImplTest {

  static class Fixture {
    final AiCatalogEntryRepository entries = mock(AiCatalogEntryRepository.class);
    final AiMcpServerDefinitionService servers = mock(AiMcpServerDefinitionService.class);
    final AiCatalogServiceImpl service = new AiCatalogServiceImpl(entries, mock(AiCatalogSyncStateRepository.class), servers,
        mock(AiScopeAccessPolicy.class), new AiCatalogProperties(), mock(OfficialMcpRegistrySync.class),
        mock(AiRuntimeMcpProfileService.class), new ObjectMapper());
  }

  @Test
  void mixedPageCrossesTheActualInternalCountWithoutTruncationOrOverFetching() {
    var fixture = new Fixture();
    var last = new McpCatalogRegistration("internal-520", "code-520", null, "Actual520", null,
        McpServerStatus.READY, AiResourceScope.TENANT, null, null);
    when(fixture.servers.findCatalog(isNull(), eq(PageRequest.of(26, 20))))
        .thenReturn(new PageImpl<>(List.of(last), PageRequest.of(26, 20), 521));
    when(fixture.entries.findAllActiveBySource(eq(CatalogSource.OFFICIAL_MCP), eq(CatalogEntryStatus.ACTIVE), eq(""), any()))
        .thenAnswer(invocation -> {
          Pageable request = invocation.getArgument(3);
          assertThat(request.getOffset()).isZero(); assertThat(request.getPageSize()).isEqualTo(19);
          var entries = java.util.stream.IntStream.range(0, 19).mapToObj(index -> {
            var entry = new AiCatalogEntry(); entry.setId("official-" + index); entry.setSource(CatalogSource.OFFICIAL_MCP);
            entry.setKind(CatalogPackageKind.MCP_SERVER); entry.setStatus(CatalogEntryStatus.ACTIVE); return entry;
          }).toList();
          return new PageImpl<>(entries, request, 60);
        });
    var page = fixture.service.findAll(null, null, null, 26, 20);
    assertThat(page.totalElements()).isEqualTo(581); assertThat(page.totalPages()).isEqualTo(30);
    assertThat(page.content()).hasSize(20);
    assertThat(page.content().getFirst().id()).isEqualTo("internal:mcp:internal-520");
    verify(fixture.servers, never()).limit(any(), any());
  }

  @Test
  void aFullInternalPageCountsOfficialEntriesWithoutHydratingAnUnusedDescriptor() {
    var fixture = new Fixture();
    var rows = java.util.stream.IntStream.range(0, 20).mapToObj(index ->
        new McpCatalogRegistration("actual-" + index, "code", null, "name", null, null, AiResourceScope.SYSTEM, null, null)).toList();
    when(fixture.servers.findCatalog(eq("%_!"), any())).thenReturn(new PageImpl<>(rows, PageRequest.of(0, 20), 521));
    when(fixture.entries.countActiveBySource(CatalogSource.OFFICIAL_MCP, CatalogEntryStatus.ACTIVE, "%!%!_!!%"))
        .thenReturn(7L);
    var page = fixture.service.findAll(" %_! ", null, null, 0, 20);
    assertThat(page.totalElements()).isEqualTo(528);
    verify(fixture.entries, never()).findAllActiveBySource(any(), any(), anyString(), any());
  }

  @Test
  void officialOnlyPagesRetainTheirOwnOffsetAndDoNotReadInternalServers() {
    var fixture = new Fixture();
    when(fixture.entries.findAllActiveBySource(any(), any(), eq("%actual520%"), any())).thenAnswer(invocation -> {
      Pageable request = invocation.getArgument(3);
      assertThat(request.getOffset()).isEqualTo(520); assertThat(request.getPageSize()).isEqualTo(20);
      return new PageImpl<>(List.of(), request, 521);
    });
    assertThat(fixture.service.findAll("Actual520", CatalogSource.OFFICIAL_MCP, null, 26, 20).totalElements()).isEqualTo(521);
    verify(fixture.servers, never()).findCatalog(any(), any());
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> fixture.service.findAll("a".repeat(201), null, null, 0, 20))
        .isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> fixture.service.findAll(null, null, null, Integer.MAX_VALUE, 100))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aMixedPageAfterTheBoundaryUsesTheActualOfficialRowOffset() {
    var fixture = new Fixture(); var request = PageRequest.of(27, 20);
    when(fixture.servers.findCatalog(isNull(), eq(request))).thenReturn(new PageImpl<>(List.of(), request, 521));
    when(fixture.entries.findAllActiveBySource(any(), any(), eq(""), any())).thenAnswer(invocation -> {
      Pageable official = invocation.getArgument(3);
      assertThat(official.getOffset()).isEqualTo(19); assertThat(official.getPageSize()).isEqualTo(20);
      return new PageImpl<>(List.of(), official, 60);
    });
    assertThat(fixture.service.findAll(null, null, null, 27, 20).totalElements()).isEqualTo(581);
  }

  @Test
  void importsOfficialOciPackageAsSafeRuntimeDraft() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    final AiCatalogEntryRepository entries =
        mock(AiCatalogEntryRepository.class);
    final AiRuntimeMcpProfileService runtimeProfiles =
        mock(AiRuntimeMcpProfileService.class);
    McpServerDescriptor descriptor = new McpServerDescriptor(
        "https://example.com/schema.json",
        "io.example/server",
        "Example Server",
        "Example description",
        "1.0.0",
        new McpServerDescriptor.Repository(
            "https://github.com/example/server",
            "github",
            "example/server",
            null
        ),
        null,
        List.of(new McpServerDescriptor.Package(
            "oci",
            "ghcr.io/example/server:1.0.0",
            "1.0.0",
            null,
            null,
            null,
            new McpServerDescriptor.Transport(
                "stdio", null, List.of(), Map.of()
            ),
            List.of(),
            List.of(),
            List.of()
        )),
        List.of(),
        "{\"name\":\"io.example/server\",\"version\":\"1.0.0\"}"
    );
    AiCatalogEntry entry = new AiCatalogEntry();
    entry.setId("catalog-1");
    entry.setExternalKey("a".repeat(64));
    entry.setSource(CatalogSource.OFFICIAL_MCP);
    entry.setKind(CatalogPackageKind.MCP_SERVER);
    entry.setStatus(CatalogEntryStatus.ACTIVE);
    entry.setRegistryName(descriptor.name());
    entry.setVersion(descriptor.version());
    entry.setDescriptorJson(objectMapper.writeValueAsString(descriptor));
    when(entries.findActiveById("catalog-1")).thenReturn(Optional.of(entry));
    when(entries.findAllActiveBySource(eq(CatalogSource.OFFICIAL_MCP), eq(CatalogEntryStatus.ACTIVE), eq(""), any()))
        .thenReturn(new PageImpl<>(List.of(entry), PageRequest.of(0, 20), 1));
    AiCatalogServiceImpl service = new AiCatalogServiceImpl(
        entries,
        mock(AiCatalogSyncStateRepository.class),
        mock(AiMcpServerDefinitionService.class),
        mock(AiScopeAccessPolicy.class),
        new AiCatalogProperties(),
        mock(OfficialMcpRegistrySync.class),
        runtimeProfiles,
        objectMapper
    );

    service.importOfficialMcpPackage(
        "catalog-1",
        new AiCatalogRuntimeImportRequest("example", "Example", 0)
    );

    ArgumentCaptor<RuntimeMcpProfileImportRequest> captor =
        ArgumentCaptor.forClass(RuntimeMcpProfileImportRequest.class);
    verify(runtimeProfiles).importDraft(captor.capture());
    RuntimeMcpProfileImportRequest imported = captor.getValue();
    assertThat(imported.descriptor().descriptorJson())
        .isEqualTo(descriptor.rawJson());
    assertThat(imported.spec().artifact().type())
        .isEqualTo(RuntimeMcpProfileSpec.ArtifactType.OCI);
    assertThat(imported.spec().artifact().reference())
        .isEqualTo("ghcr.io/example/server:1.0.0");
    assertThat(imported.spec().transport().type())
        .isEqualTo(RuntimeMcpProfileSpec.TransportType.STDIO);
    assertThat(imported.spec().network().mode())
        .isEqualTo(RuntimeMcpProfileSpec.NetworkMode.NONE);
    var catalog = service.findAll(null, CatalogSource.OFFICIAL_MCP, null, 0, 20).content().getFirst();
    assertThat(catalog.importable()).isTrue(); assertThat(catalog.runtimeImportable()).isTrue();
    assertThat(catalog.remoteImportable()).isFalse();
    entry.setEndpointUrl("https://example.com/mcp"); entry.setTransportType("streamable-http");
    assertThat(service.findAll(null, CatalogSource.OFFICIAL_MCP, null, 0, 20).content().getFirst().remoteImportable()).isTrue();
  }
}
