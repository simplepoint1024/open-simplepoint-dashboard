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
                "url": "https://github.com/simplepoint/echo",
                "source": "github"
              },
              "websiteUrl": "https://example.com",
              "packages": [{
                "registryType": "oci",
                "identifier": "ghcr.io/simplepoint/echo:1.2.3",
                "version": "1.2.3",
                "runtimeHint": "docker",
                "runtimeArguments": [{
                  "type": "named",
                  "name": "--pull",
                  "value": "never"
                }],
                "packageArguments": [{
                  "type": "positional",
                  "valueHint": "workspace",
                  "isRequired": true,
                  "format": "filepath"
                }],
                "environmentVariables": [{
                  "name": "ECHO_TOKEN",
                  "isRequired": true,
                  "isSecret": true
                }],
                "transport": {"type": "stdio"}
              }],
              "remotes": [
                {"type": "sse", "url": "https://example.com/sse"},
                {
                  "type": "streamable-http",
                  "url": "https://example.com/{tenant}/mcp",
                  "variables": {
                    "tenant": {"isRequired": true, "format": "string"}
                  }
                }
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
      assertThat(entry.endpointUrl())
          .isEqualTo("https://example.com/{tenant}/mcp");
      assertThat(entry.repositoryUrl())
          .isEqualTo("https://github.com/simplepoint/echo");
      assertThat(entry.updatedAt()).isNotNull();
      assertThat(entry.descriptor().packages()).singleElement().satisfies(pkg -> {
        assertThat(pkg.registryType()).isEqualTo("oci");
        assertThat(pkg.identifier()).isEqualTo("ghcr.io/simplepoint/echo:1.2.3");
        assertThat(pkg.transport().type()).isEqualTo("stdio");
        assertThat(pkg.runtimeArguments()).singleElement()
            .satisfies(argument -> assertThat(argument.name()).isEqualTo("--pull"));
        assertThat(pkg.packageArguments()).singleElement()
            .satisfies(argument -> assertThat(argument.valueHint())
                .isEqualTo("workspace"));
        assertThat(pkg.environmentVariables()).singleElement()
            .satisfies(input -> {
              assertThat(input.name()).isEqualTo("ECHO_TOKEN");
              assertThat(input.secret()).isTrue();
            });
      });
      assertThat(entry.descriptor().remotes()).hasSize(2);
      assertThat(entry.descriptor().remotes().get(1).variables())
          .containsKey("tenant");
    });
  }

  @Test
  void parsesPackageOnlyDescriptorWithoutMakingItRemoteImportable() {
    HttpOfficialMcpRegistryClient client = new HttpOfficialMcpRegistryClient(
        new AiCatalogProperties(),
        new ObjectMapper()
    );

    OfficialRegistryPage page = client.parsePage("""
        {
          "servers": [{
            "server": {
              "name": "io.github.simplepoint/local",
              "description": "A local package",
              "version": "2.0.0",
              "packages": [{
                "registryType": "npm",
                "identifier": "@simplepoint/local",
                "version": "2.0.0",
                "transport": {"type": "stdio"}
              }]
            },
            "_meta": {
              "io.modelcontextprotocol.registry/official": {
                "status": "active"
              }
            }
          }],
          "metadata": {}
        }
        """);

    assertThat(page.entries()).singleElement().satisfies(entry -> {
      assertThat(entry.transportType()).isNull();
      assertThat(entry.endpointUrl()).isNull();
      assertThat(entry.descriptor().packages()).singleElement()
          .satisfies(pkg -> assertThat(pkg.identifier())
              .isEqualTo("@simplepoint/local"));
      assertThat(entry.descriptor().remotes()).isEmpty();
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
