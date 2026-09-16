package org.simplepoint.plugin.ai.skill.service.artifact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Generates a deterministic, code-free OCI graph for one Skill Manifest. */
@Component
public class SkillOciArtifactGenerator {

  private static final String CONTENT_HASH_ANNOTATION =
      "io.simplepoint.skill.content-hash";

  private static final String REVISION_ANNOTATION =
      "io.simplepoint.skill.draft-revision";

  private final SkillArtifactProperties properties;

  private final ObjectMapper canonicalMapper;

  /** Creates the deterministic generator. */
  public SkillOciArtifactGenerator(
      final SkillArtifactProperties properties,
      final ObjectMapper objectMapper
  ) {
    this.properties = properties;
    this.canonicalMapper = objectMapper.copy()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
  }

  /**
   * Generates config, Skill Manifest layer and OCI Manifest bytes.
   *
   * @param manifest canonical Skill Manifest
   * @param skillCode immutable Skill code
   * @param semanticVersion target semantic version
   * @param draftRevision source Draft Revision number
   * @return deterministic OCI graph and digests
   */
  public GeneratedSkillOciArtifact generate(
      final Map<String, Object> manifest,
      final String skillCode,
      final String semanticVersion,
      final long draftRevision
  ) {
    if (manifest == null || manifest.isEmpty()) {
      throw new IllegalArgumentException("Skill Manifest must not be empty");
    }
    if (skillCode == null || skillCode.isBlank()
        || semanticVersion == null || semanticVersion.isBlank()
        || draftRevision < 1) {
      throw new IllegalArgumentException(
          "Skill Artifact identity is invalid"
      );
    }
    byte[] skillManifest = write(manifest, "Skill Manifest");
    assertSize(
        skillManifest,
        properties.getMaximumSkillManifestBytes(),
        "Skill Manifest"
    );
    String skillManifestDigest = digest(skillManifest);
    String contentHash = skillManifestDigest.substring("sha256:".length());

    byte[] config = write(Map.of(
        "schemaVersion", "1.0",
        "manifestMediaType",
        OciSkillArtifactVerifier.SKILL_MANIFEST_MEDIA_TYPE,
        "manifestDigest", skillManifestDigest
    ), "Skill Artifact config");
    assertSize(config, properties.getMaximumConfigBytes(), "Skill config");
    String configDigest = digest(config);

    Map<String, Object> configDescriptor = Map.of(
        "mediaType", OciSkillArtifactVerifier.SKILL_CONFIG_MEDIA_TYPE,
        "digest", configDigest,
        "size", config.length
    );
    Map<String, Object> layerDescriptor = Map.of(
        "mediaType", OciSkillArtifactVerifier.SKILL_MANIFEST_MEDIA_TYPE,
        "digest", skillManifestDigest,
        "size", skillManifest.length,
        "annotations", Map.of(
            "org.opencontainers.image.title", "skill-manifest.json"
        )
    );
    byte[] ociManifest = write(Map.of(
        "schemaVersion", 2,
        "mediaType", OciSkillArtifactVerifier.OCI_MANIFEST_MEDIA_TYPE,
        "artifactType", OciSkillArtifactVerifier.SKILL_ARTIFACT_TYPE,
        "config", configDescriptor,
        "layers", List.of(layerDescriptor),
        "annotations", Map.of(
            "org.opencontainers.image.title", skillCode,
            "org.opencontainers.image.version", semanticVersion,
            CONTENT_HASH_ANNOTATION, contentHash,
            REVISION_ANNOTATION, Long.toString(draftRevision)
        )
    ), "OCI Artifact manifest");
    assertSize(
        ociManifest,
        properties.getMaximumOciManifestBytes(),
        "OCI Artifact manifest"
    );
    return new GeneratedSkillOciArtifact(
        config,
        configDigest,
        skillManifest,
        skillManifestDigest,
        contentHash,
        ociManifest,
        digest(ociManifest)
    );
  }

  private byte[] write(final Object value, final String label) {
    try {
      return canonicalMapper.writeValueAsBytes(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException(label + " cannot be encoded", ex);
    }
  }

  private void assertSize(
      final byte[] content,
      final Integer maximumBytes,
      final String label
  ) {
    if (maximumBytes == null || maximumBytes < 1
        || content.length < 1 || content.length > maximumBytes) {
      throw new IllegalArgumentException(
          label + " exceeds the configured size limit"
      );
    }
  }

  private String digest(final byte[] value) {
    try {
      return "sha256:" + HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value)
      );
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }
}
