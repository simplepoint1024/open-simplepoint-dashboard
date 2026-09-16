package org.simplepoint.plugin.ai.catalog.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileImportRequest;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeMcpProfileService;
import org.simplepoint.plugin.ai.skill.api.service.AiSkillService;

class AiCatalogServiceImplTest {

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
    AiCatalogServiceImpl service = new AiCatalogServiceImpl(
        entries,
        mock(AiCatalogSyncStateRepository.class),
        mock(AiMcpServerDefinitionService.class),
        mock(AiSkillService.class),
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
  }
}
