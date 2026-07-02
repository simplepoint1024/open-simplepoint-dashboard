package org.simplepoint.plugin.core;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.api.manifest.PluginManifest;

class VersionCompatibilityVerifierTest {

  @Test
  void verify_acceptsMatchingRuntimeVersions() {
    PluginManifest manifest = manifest();
    manifest.setCoreVersion(">=1.0.0 <2.0.0");
    manifest.setFrontendSdkVersion("^3.1.0");
    VersionCompatibilityVerifier verifier =
        new VersionCompatibilityVerifier(new PluginRuntimeVersions("1.5.0", "3.2.0"));

    verifier.verify(manifest);
  }

  @Test
  void verify_rejectsIncompatibleCoreVersion() {
    PluginManifest manifest = manifest();
    manifest.setCoreVersion(">=2.0.0");
    VersionCompatibilityVerifier verifier =
        new VersionCompatibilityVerifier(new PluginRuntimeVersions("1.5.0", "3.2.0"));

    assertThatThrownBy(() -> verifier.verify(manifest))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires core version >=2.0.0");
  }

  @Test
  void verify_rejectsMissingRuntimeVersionWhenRequirementExists() {
    PluginManifest manifest = manifest();
    manifest.setFrontendSdkVersion(">=1.0.0");
    VersionCompatibilityVerifier verifier =
        new VersionCompatibilityVerifier(new PluginRuntimeVersions("1.5.0", null));

    assertThatThrownBy(() -> verifier.verify(manifest))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("runtime frontend SDK version is not configured");
  }

  private static PluginManifest manifest() {
    PluginManifest manifest = new PluginManifest();
    manifest.setId("org.example.plugin");
    manifest.setName("Example");
    manifest.setVersion("1.0.0");
    return manifest;
  }
}
