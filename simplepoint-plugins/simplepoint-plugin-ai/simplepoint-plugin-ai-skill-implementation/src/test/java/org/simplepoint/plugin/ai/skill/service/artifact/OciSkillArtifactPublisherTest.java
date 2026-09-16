package org.simplepoint.plugin.ai.skill.service.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OciSkillArtifactPublisherTest {

  private HttpServer registry;

  private String registryHost;

  private SkillArtifactProperties properties;

  private GeneratedSkillOciArtifact artifact;

  private final Map<String, byte[]> uploadedBlobs = new HashMap<>();

  private final List<byte[]> uploadedManifests = new ArrayList<>();

  @BeforeEach
  void setUp() throws IOException {
    registry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    registryHost = "127.0.0.1:" + registry.getAddress().getPort();
    properties = new SkillArtifactProperties();
    properties.setManagedRegistryEnabled(true);
    properties.setManagedRegistry(registryHost);
    properties.setManagedRepositoryPrefix("simplepoint/skills");
    properties.setAllowedRegistries(List.of(registryHost));
    properties.setAllowedTokenHosts(List.of(registryHost));
    properties.setInsecureRegistries(List.of(registryHost));
    artifact = new SkillOciArtifactGenerator(
        properties,
        new ObjectMapper()
    ).generate(manifest(), "document-summary", "1.2.3", 7);
  }

  @AfterEach
  void tearDown() {
    registry.stop(0);
  }

  @Test
  void pushesMissingBlobsBeforeAssigningVersionTag() {
    registry.createContext(
        "/v2/simplepoint/skills/document-summary/",
        this::handleRegistry
    );
    registry.start();

    PushedSkillOciArtifact result = publisher().push(
        "document-summary",
        "1.2.3",
        artifact
    );
    PushedSkillOciArtifact retry = publisher().push(
        "document-summary",
        "1.2.3",
        artifact
    );

    assertThat(uploadedBlobs).hasSize(2)
        .containsEntry(artifact.configDigest(), artifact.config())
        .containsEntry(
            artifact.skillManifestDigest(),
            artifact.skillManifest()
    );
    assertThat(uploadedManifests).containsExactly(
        artifact.ociManifest(),
        artifact.ociManifest()
    );
    assertThat(result.artifactReference()).isEqualTo(
        registryHost + "/simplepoint/skills/document-summary:1.2.3"
    );
    assertThat(result.artifactDigest())
        .isEqualTo(artifact.ociManifestDigest());
    assertThat(result.contentHash()).isEqualTo(artifact.contentHash());
    assertThat(retry).isEqualTo(result);
  }

  @Test
  void rejectsCrossRegistryUploadLocation() {
    registry.createContext(
        "/v2/simplepoint/skills/document-summary/",
        exchange -> {
          if ("HEAD".equals(exchange.getRequestMethod())) {
            respond(exchange, 404, null);
            return;
          }
          exchange.getResponseHeaders().set(
              "Location",
              "https://attacker.example/v2/stolen/blobs/uploads/1"
          );
          respond(exchange, 202, null);
        }
    );
    registry.start();

    assertThatThrownBy(() -> publisher().push(
        "document-summary",
        "1.2.3",
        artifact
    )).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Location is not allowed");
    assertThat(uploadedBlobs).isEmpty();
    assertThat(uploadedManifests).isEmpty();
  }

  private void handleRegistry(final HttpExchange exchange) throws IOException {
    String method = exchange.getRequestMethod();
    String path = exchange.getRequestURI().getPath();
    if ("HEAD".equals(method) && path.contains("/blobs/")) {
      String digest = path.substring(path.indexOf("/blobs/") + 7);
      respond(exchange, uploadedBlobs.containsKey(digest) ? 200 : 404, null);
      return;
    }
    if ("POST".equals(method) && path.endsWith("/blobs/uploads/")) {
      exchange.getResponseHeaders().set(
          "Location",
          path + "upload-" + uploadedBlobs.size()
      );
      respond(exchange, 202, null);
      return;
    }
    if ("PUT".equals(method) && path.contains("/blobs/uploads/")) {
      String digest = queryParameter(exchange, "digest");
      uploadedBlobs.put(digest, exchange.getRequestBody().readAllBytes());
      exchange.getResponseHeaders().set("Docker-Content-Digest", digest);
      respond(exchange, 201, null);
      return;
    }
    if ("PUT".equals(method) && path.endsWith("/manifests/1.2.3")) {
      assertThat(exchange.getRequestHeaders().getFirst("Content-Type"))
          .isEqualTo(OciSkillArtifactVerifier.OCI_MANIFEST_MEDIA_TYPE);
      uploadedManifests.add(exchange.getRequestBody().readAllBytes());
      exchange.getResponseHeaders().set(
          "Docker-Content-Digest",
          artifact.ociManifestDigest()
      );
      respond(exchange, 201, null);
      return;
    }
    respond(exchange, 404, null);
  }

  private OciSkillArtifactPublisher publisher() {
    return new OciSkillArtifactPublisher(properties, new ObjectMapper());
  }

  private String queryParameter(
      final HttpExchange exchange,
      final String name
  ) {
    String query = exchange.getRequestURI().getRawQuery();
    for (String parameter : query.split("&")) {
      String[] pair = parameter.split("=", 2);
      if (name.equals(pair[0])) {
        return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
      }
    }
    throw new IllegalArgumentException("Missing query parameter " + name);
  }

  private void respond(
      final HttpExchange exchange,
      final int status,
      final byte[] body
  ) throws IOException {
    if (body == null) {
      exchange.sendResponseHeaders(status, -1);
    } else {
      exchange.sendResponseHeaders(status, body.length);
      exchange.getResponseBody().write(body);
    }
    exchange.close();
  }

  private Map<String, Object> manifest() {
    return Map.of(
        "apiVersion", "simplepoint.io/v1alpha1",
        "kind", "Skill",
        "metadata", Map.of(
            "name", "document-summary",
            "version", "1.2.3"
        ),
        "spec", Map.of("workflow", Map.of("steps", List.of()))
    );
  }
}
