package org.simplepoint.plugin.rbac.menu.service.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RemoteEntryVersionerTest {

  private final RemoteEntryVersioner versioner = new RemoteEntryVersioner();

  @Test
  void versioned_addsPluginVersionToken() {
    String entry = versioner.versioned(
        "https://cdn.example.com/app/mf-manifest.json",
        "plugin.analytics",
        "1.0.0",
        "2.0.0",
        "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789");

    assertThat(entry).isEqualTo("https://cdn.example.com/app/mf-manifest.json"
        + "?_sp_plugin=plugin.analytics"
        + "&_sp_v=remote%3A2.0.0%3Aplugin%3A1.0.0%3Asha%3Aabcdef012345");
  }

  @Test
  void versioned_preservesExistingQueryAndFragmentAndReplacesManagedParams() {
    String entry = versioner.versioned(
        "https://cdn.example.com/app/mf-manifest.json?lang=zh&_sp_v=old#manifest",
        "plugin.analytics",
        "1.0.0",
        null,
        null);

    assertThat(entry).isEqualTo("https://cdn.example.com/app/mf-manifest.json"
        + "?lang=zh"
        + "&_sp_plugin=plugin.analytics"
        + "&_sp_v=plugin%3A1.0.0"
        + "#manifest");
  }

  @Test
  void versioned_returnsCanonicalEntryForPlatformRemote() {
    String entry = versioner.versioned(
        " https://cdn.example.com/common/mf-manifest.json ",
        null,
        "1.0.0",
        null,
        null);

    assertThat(entry).isEqualTo("https://cdn.example.com/common/mf-manifest.json");
  }
}
