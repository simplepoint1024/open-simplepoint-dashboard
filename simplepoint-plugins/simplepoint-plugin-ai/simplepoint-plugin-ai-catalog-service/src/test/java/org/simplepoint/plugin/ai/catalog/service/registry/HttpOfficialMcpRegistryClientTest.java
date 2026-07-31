package org.simplepoint.plugin.ai.catalog.service.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class HttpOfficialMcpRegistryClientTest {

  @Test
  void parsesOfficialRegistryPageAndSelectsStreamableHttp() {
    HttpOfficialMcpRegistryClient client = new HttpOfficialMcpRegistryClient(
        new AiCatalogProperties(),
        new ObjectMapper()
    );

    OfficialRegistryPage page = client.parsePage("""
        {
          "servers": [{
            "server": {
              "name": "io.github.simplepoint/echo",
              "title": "Echo",
              "description": "A test server",
              "version": "1.2.3",
              "repository": {
                "url": "https://github.com/simplepoint/echo"
              },
              "websiteUrl": "https://example.com",
              "remotes": [
                {"type": "sse", "url": "https://example.com/sse"},
                {"type": "streamable-http", "url": "https://example.com/mcp"}
              ]
            },
            "_meta": {
              "io.modelcontextprotocol.registry/official": {
                "status": "active",
                "publishedAt": "2026-07-01T00:00:00Z",
                "updatedAt": "2026-07-02T00:00:00Z"
              }
            }
          }],
          "metadata": {"nextCursor": "next-page"}
        }
        """);

    assertThat(page.nextCursor()).isEqualTo("next-page");
    assertThat(page.entries()).singleElement().satisfies(entry -> {
      assertThat(entry.name()).isEqualTo("io.github.simplepoint/echo");
      assertThat(entry.version()).isEqualTo("1.2.3");
      assertThat(entry.transportType()).isEqualTo("streamable-http");
      assertThat(entry.endpointUrl()).isEqualTo("https://example.com/mcp");
      assertThat(entry.repositoryUrl())
          .isEqualTo("https://github.com/simplepoint/echo");
      assertThat(entry.updatedAt()).isNotNull();
    });
  }

  @Test
  void rejectsMalformedRegistryEnvelope() {
    HttpOfficialMcpRegistryClient client = new HttpOfficialMcpRegistryClient(
        new AiCatalogProperties(),
        new ObjectMapper()
    );

    assertThatThrownBy(() -> client.parsePage("{\"metadata\":{}}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("servers array");
  }
}
