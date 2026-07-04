package org.simplepoint.plugin.rbac.resource.service.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.plugin.api.Plugin;
import org.simplepoint.plugin.api.PluginArtifact;
import org.simplepoint.plugin.api.manifest.PluginManifest;
import org.simplepoint.plugin.rbac.resource.api.repository.RemoteModuleRepository;
import org.simplepoint.plugin.rbac.resource.api.repository.ResourceRepository;
import org.simplepoint.security.ResourceDeclaration;
import org.simplepoint.security.entity.MicroModule;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceType;
import org.simplepoint.security.service.ResourceService;

@ExtendWith(MockitoExtension.class)
class PluginResourceContributionHandlerTest {

  @Mock
  RemoteModuleRepository remoteModuleRepository;

  @Mock
  ResourceRepository resourceRepository;

  @Mock
  ResourceService resourceService;

  @InjectMocks
  PluginResourceContributionHandler handler;

  @Test
  void validate_acceptsResourceManifestWithoutWritingResources() {
    Plugin plugin = plugin();
    when(remoteModuleRepository.findAll(Map.of("serviceName", "analytics"))).thenReturn(List.of());
    when(remoteModuleRepository.findAll(Map.of("entry", "http://cdn.example.com/analytics/mf-manifest.json")))
        .thenReturn(List.of());
    when(resourceRepository.findAllByCodes(anyCollection())).thenReturn(List.of());

    handler.validate(plugin);

    verify(resourceService, never()).sync(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anySet());
  }

  @Test
  void validate_rejectsRemoteOwnedByAnotherPlugin() {
    MicroModule existing = new MicroModule();
    existing.setId("remote-id");
    existing.setServiceName("analytics");
    existing.setPluginId("another.plugin");

    when(remoteModuleRepository.findAll(Map.of("serviceName", "analytics"))).thenReturn(List.of(existing));

    assertThatThrownBy(() -> handler.validate(plugin()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("frontend remote 'analytics' is not owned by plugin plugin.analytics");
  }

  @Test
  void validate_rejectsResourceOwnedByAnotherPlugin() {
    Resource existing = new Resource();
    existing.setCode("analytics.dashboard");
    existing.setPluginId("another.plugin");
    when(remoteModuleRepository.findAll(Map.of("serviceName", "analytics"))).thenReturn(List.of());
    when(remoteModuleRepository.findAll(Map.of("entry", "http://cdn.example.com/analytics/mf-manifest.json")))
        .thenReturn(List.of());
    when(resourceRepository.findAllByCodes(anyCollection())).thenReturn(List.of(existing));

    assertThatThrownBy(() -> handler.validate(plugin()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Resource code already owned by another plugin: analytics.dashboard");
  }

  @Test
  void validateBatch_rejectsDuplicateRemoteAcrossPluginCandidates() {
    Plugin reports = pluginWithRemote(
        "plugin.reports",
        "analytics",
        "http://cdn.example.com/reports/mf-manifest.json");

    assertThatThrownBy(() -> handler.validate(List.of(plugin(), reports)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("frontend remote 'analytics' is declared by multiple plugin candidates");
  }

  @Test
  void validateBatch_rejectsDuplicateResourceCodeAcrossPluginCandidates() {
    Plugin another = pluginWithResource("plugin.reports", "analytics.dashboard", "/reports");

    assertThatThrownBy(() -> handler.validate(List.of(plugin(), another)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Duplicate resource code in plugin batch: analytics.dashboard");
  }

  @Test
  void installed_registersRemoteAndSynchronizesResources() {
    Plugin plugin = plugin();
    when(remoteModuleRepository.findAll(Map.of("pluginId", "plugin.analytics", "serviceName", "analytics")))
        .thenReturn(List.of());

    handler.installed(plugin);

    ArgumentCaptor<MicroModule> remoteCaptor = ArgumentCaptor.forClass(MicroModule.class);
    verify(remoteModuleRepository).save(remoteCaptor.capture());
    assertThat(remoteCaptor.getValue().getPluginId()).isEqualTo("plugin.analytics");
    assertThat(remoteCaptor.getValue().getPluginVersion()).isEqualTo("1.0.0");
    assertThat(remoteCaptor.getValue().getRemoteVersion()).isEqualTo("2.0.0");
    assertThat(remoteCaptor.getValue().getPluginArtifactSha256()).isEqualTo(
        "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789");
    assertThat(remoteCaptor.getValue().getServiceName()).isEqualTo("analytics");
    assertThat(remoteCaptor.getValue().getEntry()).isEqualTo("http://cdn.example.com/analytics/mf-manifest.json");

    ArgumentCaptor<Set<ResourceDeclaration>> resourcesCaptor = ArgumentCaptor.forClass(Set.class);
    verify(resourceService).sync(org.mockito.ArgumentMatchers.eq("plugin.analytics"), resourcesCaptor.capture());
    ResourceDeclaration root = resourcesCaptor.getValue().iterator().next();
    assertThat(root.getPluginId()).isEqualTo("plugin.analytics");
    assertThat(root.getCode()).isEqualTo("analytics.root");
    assertThat(root.getType()).isEqualTo(ResourceType.MODULE);
    assertThat(root.getChildren()).hasSize(1);
    ResourceDeclaration page = root.getChildren().iterator().next();
    assertThat(page.getCode()).isEqualTo("analytics.dashboard");
    assertThat(page.getPath()).isEqualTo("/analytics");
    assertThat(page.getChildren()).extracting(ResourceDeclaration::getCode).containsExactly("analytics.refresh");
  }

  @Test
  void uninstalling_removesOwnedResourcesAndRemotes() {
    Resource resource = new Resource();
    resource.setId("resource-id");
    resource.setPluginId("plugin.analytics");
    MicroModule remote = new MicroModule();
    remote.setId("remote-id");
    remote.setPluginId("plugin.analytics");

    when(resourceService.findAll(Map.of("pluginId", "plugin.analytics"))).thenReturn(List.of(resource));
    when(remoteModuleRepository.findAll(Map.of("pluginId", "plugin.analytics"))).thenReturn(List.of(remote));

    handler.uninstalling(plugin());

    verify(resourceService).removeByIds(List.of("resource-id"));
    verify(remoteModuleRepository).deleteByIds(List.of("remote-id"));
  }

  private static Plugin plugin() {
    PluginManifest manifest = baseManifest("plugin.analytics");

    PluginManifest.Frontend frontend = new PluginManifest.Frontend();
    PluginManifest.RemoteContribution remote = new PluginManifest.RemoteContribution();
    remote.setName("analytics");
    remote.setEntry("http://cdn.example.com/analytics/mf-manifest.json");
    remote.setVersion("2.0.0");
    frontend.setRemotes(List.of(remote));
    manifest.setFrontend(frontend);

    PluginManifest.ResourceContribution root = resource("analytics.root", "Analytics", "MODULE", null);
    root.setRouteKind("submenu");
    PluginManifest.ResourceContribution page = resource("analytics.dashboard", "Analytics Dashboard", "PAGE", "/analytics");
    page.setComponent("analytics/Dashboard");
    page.setRouteKind("item");
    page.setGrantable(true);
    PluginManifest.ResourceContribution refresh = resource("analytics.refresh", "Refresh Analytics", "ACTION", null);
    refresh.setGrantable(true);
    page.setChildren(List.of(refresh));
    root.setChildren(List.of(page));
    manifest.setResources(List.of(root));

    PluginArtifact artifact = new PluginArtifact(
        URI.create("file:/plugins/analytics.jar"),
        42,
        "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789");
    return new Plugin(artifact, manifest, Map.of());
  }

  private static Plugin pluginWithRemote(String pluginId, String remoteName, String entry) {
    PluginManifest manifest = baseManifest(pluginId);
    PluginManifest.Frontend frontend = new PluginManifest.Frontend();
    PluginManifest.RemoteContribution remote = new PluginManifest.RemoteContribution();
    remote.setName(remoteName);
    remote.setEntry(entry);
    frontend.setRemotes(List.of(remote));
    manifest.setFrontend(frontend);
    return plugin(manifest);
  }

  private static Plugin pluginWithResource(String pluginId, String code, String path) {
    PluginManifest manifest = baseManifest(pluginId);
    manifest.setResources(List.of(resource(code, code, "PAGE", path)));
    return plugin(manifest);
  }

  private static Plugin plugin(PluginManifest manifest) {
    PluginArtifact artifact = new PluginArtifact(
        URI.create("file:/plugins/" + manifest.getId() + ".jar"),
        42,
        "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789");
    return new Plugin(artifact, manifest, Map.of());
  }

  private static PluginManifest baseManifest(String pluginId) {
    PluginManifest manifest = new PluginManifest();
    manifest.setId(pluginId);
    manifest.setName(pluginId);
    manifest.setVersion("1.0.0");
    return manifest;
  }

  private static PluginManifest.ResourceContribution resource(String code, String name, String type, String path) {
    PluginManifest.ResourceContribution resource = new PluginManifest.ResourceContribution();
    resource.setCode(code);
    resource.setName(name);
    resource.setType(type);
    resource.setPath(path);
    return resource;
  }
}
