package org.simplepoint.plugin.ai.skill.service.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SkillOciArtifactGeneratorTest {

  private final SkillArtifactProperties properties =
      new SkillArtifactProperties();

  private final SkillOciArtifactGenerator generator =
      new SkillOciArtifactGenerator(properties, new ObjectMapper());

  @Test
  void generatesReproducibleOciArtifactGraph() {
    Map<String, Object> manifest = manifest();

    GeneratedSkillOciArtifact first = generator.generate(
        manifest,
        "document-summary",
        "1.2.3",
        7
    );
    GeneratedSkillOciArtifact second = generator.generate(
        manifest,
        "document-summary",
        "1.2.3",
        7
    );

    assertThat(text(first.skillManifest())).isEqualTo(
        "{\"apiVersion\":\"simplepoint.io/v1alpha1\",\"kind\":\"Skill\","
            + "\"metadata\":{\"name\":\"document-summary\","
            + "\"version\":\"1.2.3\"},\"spec\":{\"workflow\":{"
            + "\"steps\":[]}}}"
    );
    assertThat(first.skillManifestDigest())
        .isEqualTo(
            "sha256:f56f38f69bba2b18d89cc70d149895fd"
                + "b181e2c736da3ee9b28f4c228106ede4"
        );
    assertThat(first.contentHash())
        .isEqualTo(first.skillManifestDigest().substring("sha256:".length()));
    assertThat(text(first.config())).isEqualTo(
        "{\"manifestDigest\":\"" + first.skillManifestDigest()
            + "\",\"manifestMediaType\":\""
            + OciSkillArtifactVerifier.SKILL_MANIFEST_MEDIA_TYPE
            + "\",\"schemaVersion\":\"1.0\"}"
    );
    assertThat(first.configDigest()).isEqualTo(
        "sha256:8020598c6e913b9bdd950a8eef240104"
            + "815102db78364f7c110f2424e4da5873"
    );
    assertThat(text(first.ociManifest())).isEqualTo(
        "{\"annotations\":{\"io.simplepoint.skill.content-hash\":\""
            + first.contentHash()
            + "\",\"io.simplepoint.skill.draft-revision\":\"7\","
            + "\"org.opencontainers.image.title\":\"document-summary\","
            + "\"org.opencontainers.image.version\":\"1.2.3\"},"
            + "\"artifactType\":\""
            + OciSkillArtifactVerifier.SKILL_ARTIFACT_TYPE
            + "\",\"config\":{\"digest\":\"" + first.configDigest()
            + "\",\"mediaType\":\""
            + OciSkillArtifactVerifier.SKILL_CONFIG_MEDIA_TYPE
            + "\",\"size\":" + first.config().length + "},"
            + "\"layers\":[{\"annotations\":{"
            + "\"org.opencontainers.image.title\":\"skill-manifest.json\"},"
            + "\"digest\":\"" + first.skillManifestDigest()
            + "\",\"mediaType\":\""
            + OciSkillArtifactVerifier.SKILL_MANIFEST_MEDIA_TYPE
            + "\",\"size\":" + first.skillManifest().length + "}],"
            + "\"mediaType\":\""
            + OciSkillArtifactVerifier.OCI_MANIFEST_MEDIA_TYPE
            + "\",\"schemaVersion\":2}"
    );
    assertThat(second.config()).isEqualTo(first.config());
    assertThat(second.skillManifest()).isEqualTo(first.skillManifest());
    assertThat(second.ociManifest()).isEqualTo(first.ociManifest());
    assertThat(second.ociManifestDigest())
        .isEqualTo(first.ociManifestDigest());
  }

  @Test
  void returnsDefensiveCopiesAndEnforcesConfiguredBounds() {
    GeneratedSkillOciArtifact artifact = generator.generate(
        manifest(),
        "document-summary",
        "1.2.3",
        7
    );
    byte[] content = artifact.skillManifest();
    content[0] = 'x';
    assertThat(artifact.skillManifest()[0]).isEqualTo((byte) '{');

    properties.setMaximumSkillManifestBytes(16);
    assertThatThrownBy(() -> generator.generate(
        manifest(),
        "document-summary",
        "1.2.3",
        7
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("size limit");
  }

  private Map<String, Object> manifest() {
    return Map.of(
        "apiVersion", "simplepoint.io/v1alpha1",
        "kind", "Skill",
        "metadata", Map.of(
            "name", "document-summary",
            "version", "1.2.3"
        ),
        "spec", Map.of("workflow", Map.of("steps", java.util.List.of()))
    );
  }

  private String text(final byte[] value) {
    return new String(value, StandardCharsets.UTF_8);
  }
}
