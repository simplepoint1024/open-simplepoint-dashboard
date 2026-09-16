package org.simplepoint.plugin.rbac.resource.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.plugin.rbac.resource.api.repository.RemoteModuleRepository;
import org.simplepoint.security.entity.MicroModule;

@ExtendWith(MockitoExtension.class)
class RemoteModuleServiceImplTest {

  @Mock
  RemoteModuleRepository remoteModuleRepository;

  @Mock
  DetailsProviderService detailsProviderService;

  @InjectMocks
  RemoteModuleServiceImpl service;

  @Test
  void loadApps_returnsEmptySet() {
    when(remoteModuleRepository.findAll(Map.of())).thenReturn(List.of());

    assertThat(service.loadApps()).isEmpty();
  }

  @Test
  void loadApps_mapsRegisteredRemoteModules() {
    MicroModule common = new MicroModule();
    common.setServiceName("common");
    common.setEntry("http://localhost/common/mf/mf-manifest.json");
    MicroModule plugin = new MicroModule();
    plugin.setServiceName("analytics");
    plugin.setEntry("http://localhost/analytics/mf/mf-manifest.json?lang=zh");
    plugin.setPluginId("plugin.analytics");
    plugin.setPluginVersion("1.0.0");
    plugin.setRemoteVersion("2.0.0");
    plugin.setPluginArtifactSha256("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789");
    MicroModule unnamed = new MicroModule();
    unnamed.setServiceName(" ");
    unnamed.setEntry("http://localhost/ignored/mf/mf-manifest.json");
    when(remoteModuleRepository.findAll(Map.of())).thenReturn(List.of(common, plugin, unnamed));

    assertThat(service.loadApps())
        .extracting("name", "entry")
        .containsExactly(org.assertj.core.groups.Tuple.tuple(
            "common",
            "http://localhost/common/mf/mf-manifest.json"
        ), org.assertj.core.groups.Tuple.tuple(
            "analytics",
            "http://localhost/analytics/mf/mf-manifest.json?lang=zh"
                + "&_sp_plugin=plugin.analytics"
                + "&_sp_v=remote%3A2.0.0%3Aplugin%3A1.0.0%3Asha%3Aabcdef012345"
        ));
  }
}
