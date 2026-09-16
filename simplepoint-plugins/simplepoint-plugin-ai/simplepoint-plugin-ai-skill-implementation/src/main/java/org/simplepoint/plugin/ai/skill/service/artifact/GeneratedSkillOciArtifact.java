package org.simplepoint.plugin.ai.skill.service.artifact;

import java.util.Arrays;

/** Immutable byte graph for one generated OCI Skill Artifact. */
public record GeneratedSkillOciArtifact(
    byte[] config,
    String configDigest,
    byte[] skillManifest,
    String skillManifestDigest,
    String contentHash,
    byte[] ociManifest,
    String ociManifestDigest
) {

  /** Defensively copies all generated content. */
  public GeneratedSkillOciArtifact {
    config = Arrays.copyOf(config, config.length);
    skillManifest = Arrays.copyOf(skillManifest, skillManifest.length);
    ociManifest = Arrays.copyOf(ociManifest, ociManifest.length);
  }

  @Override
  public byte[] config() {
    return Arrays.copyOf(config, config.length);
  }

  @Override
  public byte[] skillManifest() {
    return Arrays.copyOf(skillManifest, skillManifest.length);
  }

  @Override
  public byte[] ociManifest() {
    return Arrays.copyOf(ociManifest, ociManifest.length);
  }
}
