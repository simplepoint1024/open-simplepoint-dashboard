package org.simplepoint.mcp.gateway.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.mcp.gateway.publication.McpPublicationRegistry;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationManifest;

class McpProtectedResourceMetadataControllerTest {

  @Test
  void protectedResourceMetadata_usesImmutablePublicationManifest() {
    McpPublicationRegistry registry = mock(McpPublicationRegistry.class);
    McpPublicationManifest manifest = new McpPublicationManifest(
        "demo",
        "Demo MCP",
        "Publication metadata test",
        "https://platform.example/mcp/demo",
        "https://identity.example",
        List.of("mcp.invoke"),
        60,
        "server-1",
        "snapshot-1",
        "2025-11-25",
        List.of(),
        List.of(),
        List.of(),
        List.of()
    );
    when(registry.manifest("demo")).thenReturn(manifest);
    McpProtectedResourceMetadataController controller =
        new McpProtectedResourceMetadataController(registry);

    Map<String, Object> metadata = controller.protectedResourceMetadata("demo");

    assertEquals("https://platform.example/mcp/demo", metadata.get("resource"));
    assertEquals(
        List.of("https://identity.example"),
        metadata.get("authorization_servers")
    );
    assertEquals(List.of("mcp.invoke"), metadata.get("scopes_supported"));
  }
}
