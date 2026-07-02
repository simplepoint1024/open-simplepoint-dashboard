package org.simplepoint.plugin.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.api.PluginArtifact;

class Sha256PluginArtifactHasherTest {

  @Test
  void hash_returnsSizeAndSha256Digest() throws Exception {
    Path artifactPath = Files.createTempFile("simplepoint-plugin-hash-test-", ".jar");
    Files.writeString(artifactPath, "abc", StandardCharsets.UTF_8);
    Sha256PluginArtifactHasher hasher = new Sha256PluginArtifactHasher();

    PluginArtifact artifact = hasher.hash(artifactPath.toUri());

    assertThat(artifact.uri()).isEqualTo(artifactPath.toUri());
    assertThat(artifact.size()).isEqualTo(3);
    assertThat(artifact.sha256()).isEqualTo(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }
}
