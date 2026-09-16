package org.simplepoint.plugin.ai.skill.service.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.skill.api.model.VerifiedSkillArtifact;

class OciSkillArtifactVerifierTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private HttpServer registry;

  private String registryHost;

  private byte[] manifest;

  private byte[] config;

  private byte[] skill;

  private String manifestDigest;

  private String configDigest;

  private String skillDigest;

  @BeforeEach
  void setUp() throws IOException {
    registry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    registryHost = "127.0.0.1:" + registry.getAddress().getPort();
    skill = json(Map.of(
        "apiVersion", "simplepoint.io/v1alpha1",
        "kind", "Skill",
        "metadata", Map.of("name", "example", "version", "1.0.0"),
        "spec", Map.of()
    ));
    skillDigest = digest(skill);
    config = json(Map.of(
        "schemaVersion", "1.0",
        "manifestMediaType",
        OciSkillArtifactVerifier.SKILL_MANIFEST_MEDIA_TYPE,
        "manifestDigest", skillDigest
    ));
    configDigest = digest(config);
    manifest = json(Map.of(
        "schemaVersion", 2,
        "mediaType", OciSkillArtifactVerifier.OCI_MANIFEST_MEDIA_TYPE,
        "artifactType", OciSkillArtifactVerifier.SKILL_ARTIFACT_TYPE,
        "config", Map.of(
            "mediaType", OciSkillArtifactVerifier.SKILL_CONFIG_MEDIA_TYPE,
            "digest", configDigest,
            "size", config.length
        ),
        "layers", List.of(Map.of(
            "mediaType", OciSkillArtifactVerifier.SKILL_MANIFEST_MEDIA_TYPE,
            "digest", skillDigest,
            "size", skill.length
        ))
    ));
    manifestDigest = digest(manifest);
    registry.createContext(
        "/v2/skills/example/manifests/1.0.0",
        exchange -> respond(
            exchange,
            manifest,
            OciSkillArtifactVerifier.OCI_MANIFEST_MEDIA_TYPE,
            manifestDigest
        )
    );
    registry.createContext(
        "/v2/skills/example/blobs/" + configDigest,
        exchange -> respond(
            exchange,
            config,
            OciSkillArtifactVerifier.SKILL_CONFIG_MEDIA_TYPE,
            configDigest
        )
    );
    registry.createContext(
        "/v2/skills/example/blobs/" + skillDigest,
        exchange -> respond(
            exchange,
            skill,
            OciSkillArtifactVerifier.SKILL_MANIFEST_MEDIA_TYPE,
            skillDigest
        )
    );
    registry.start();
  }

  @AfterEach
  void tearDown() {
    registry.stop(0);
  }

  @Test
  void pullsAndVerifiesTheCompleteSkillArtifactGraph() {
    VerifiedSkillArtifact result = verifier().verify(
        registryHost + "/skills/example:1.0.0",
        manifestDigest
    );

    assertThat(result.digest()).isEqualTo(manifestDigest);
    assertThat(result.mediaType())
        .isEqualTo(OciSkillArtifactVerifier.SKILL_ARTIFACT_TYPE);
    assertThat(result.configDigest()).isEqualTo(configDigest);
    assertThat(result.contentDigest()).isEqualTo(skillDigest);
    assertThat(result.signatureRequired()).isFalse();
    assertThat(result.signatureVerified()).isFalse();
    assertThat(result.policyHash()).matches("^sha256:[a-f0-9]{64}$");
    assertThat(result.manifest()).containsEntry(
        "apiVersion",
        "simplepoint.io/v1alpha1"
    );
  }

  @Test
  void rejectsRequestDigestThatDoesNotMatchRegistryBytes() {
    assertThatThrownBy(() -> verifier().verify(
        registryHost + "/skills/example:1.0.0",
        "sha256:" + "a".repeat(64)
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("digest does not match");
  }

  @Test
  void rejectsRegistriesOutsideTheExplicitAllowlist() {
    assertThatThrownBy(() -> verifier().verify(
        "registry.example.com/skills/example:1.0.0",
        manifestDigest
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not allowed");
  }

  private OciSkillArtifactVerifier verifier() {
    SkillArtifactProperties properties = new SkillArtifactProperties();
    properties.setAllowedRegistries(List.of(registryHost));
    properties.setInsecureRegistries(List.of(registryHost));
    properties.setAllowedTokenHosts(List.of(registryHost));
    properties.setSignatureRequired(false);
    properties.setVerifierMtlsEnabled(false);
    return new OciSkillArtifactVerifier(properties, objectMapper);
  }

  private byte[] json(final Object value) throws IOException {
    return objectMapper.writeValueAsBytes(value);
  }

  private String digest(final byte[] value) {
    try {
      return "sha256:" + HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value)
      );
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private void respond(
      final HttpExchange exchange,
      final byte[] body,
      final String mediaType,
      final String digest
  ) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", mediaType);
    exchange.getResponseHeaders().set("Docker-Content-Digest", digest);
    exchange.sendResponseHeaders(200, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }
}
