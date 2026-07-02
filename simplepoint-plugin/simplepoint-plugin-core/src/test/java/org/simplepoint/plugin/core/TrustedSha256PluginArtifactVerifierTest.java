package org.simplepoint.plugin.core;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.api.PluginArtifact;
import org.simplepoint.plugin.api.manifest.PluginManifest;

class TrustedSha256PluginArtifactVerifierTest {

  private static final String TRUSTED =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final String OTHER =
      "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

  @Test
  void verify_allowsMatchingDigest() {
    TrustedSha256PluginArtifactVerifier verifier =
        new TrustedSha256PluginArtifactVerifier(Map.of("plugin", List.of(TRUSTED.toUpperCase())), false);

    verifier.verify(artifact(TRUSTED), manifest("plugin"));
  }

  @Test
  void verify_rejectsMismatchedDigest() {
    TrustedSha256PluginArtifactVerifier verifier =
        new TrustedSha256PluginArtifactVerifier(Map.of("plugin", List.of(TRUSTED)), false);

    assertThatThrownBy(() -> verifier.verify(artifact(OTHER), manifest("plugin")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SHA-256 digest is not trusted");
  }

  @Test
  void verify_allowsUnconfiguredPluginWhenStrictModeDisabled() {
    TrustedSha256PluginArtifactVerifier verifier =
        new TrustedSha256PluginArtifactVerifier(Map.of("plugin", List.of(TRUSTED)), false);

    verifier.verify(artifact(OTHER), manifest("unconfigured"));
  }

  @Test
  void verify_rejectsUnconfiguredPluginWhenStrictModeEnabled() {
    TrustedSha256PluginArtifactVerifier verifier =
        new TrustedSha256PluginArtifactVerifier(Map.of("plugin", List.of(TRUSTED)), true);

    assertThatThrownBy(() -> verifier.verify(artifact(OTHER), manifest("unconfigured")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not have a trusted SHA-256 digest configured");
  }

  @Test
  void constructor_rejectsInvalidDigestConfiguration() {
    assertThatThrownBy(() -> new TrustedSha256PluginArtifactVerifier(Map.of("plugin", List.of("bad")), false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be 64 hex chars");
  }

  private static PluginArtifact artifact(String sha256) {
    return new PluginArtifact(URI.create("file:/plugin.jar"), 10, sha256);
  }

  private static PluginManifest manifest(String pluginId) {
    PluginManifest manifest = new PluginManifest();
    manifest.setId(pluginId);
    return manifest;
  }
}
