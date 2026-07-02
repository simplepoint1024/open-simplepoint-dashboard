package org.simplepoint.plugin.rbac.menu.service.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
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
import org.simplepoint.plugin.rbac.core.api.repository.PermissionsRepository;
import org.simplepoint.plugin.rbac.core.api.service.PermissionsService;
import org.simplepoint.plugin.rbac.menu.api.repository.MenuRepository;
import org.simplepoint.plugin.rbac.menu.api.repository.RemoteModuleRepository;
import org.simplepoint.plugin.rbac.menu.api.service.MicroAppService;
import org.simplepoint.plugin.rbac.tenant.api.entity.Feature;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.FeaturePermissionsRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.repository.ApplicationFeatureRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.FeaturePermissionRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.FeatureRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.FeatureService;
import org.simplepoint.plugin.rbac.tenant.api.service.PermissionVersionRefreshService;
import org.simplepoint.security.entity.Menu;
import org.simplepoint.security.entity.MicroModule;
import org.simplepoint.security.entity.Permissions;
import org.simplepoint.security.pojo.dto.MenuFeaturesRelevanceDto;
import org.simplepoint.security.service.MenuService;

@ExtendWith(MockitoExtension.class)
class PluginRbacContributionHandlerTest {

  @Mock
  RemoteModuleRepository remoteModuleRepository;
  @Mock
  MenuRepository menuRepository;
  @Mock
  PermissionsRepository permissionsRepository;
  @Mock
  FeatureRepository featureRepository;
  @Mock
  FeaturePermissionRelevanceRepository featurePermissionRelevanceRepository;
  @Mock
  ApplicationFeatureRelevanceRepository applicationFeatureRelevanceRepository;
  @Mock
  MicroAppService microAppService;
  @Mock
  MenuService menuService;
  @Mock
  PermissionsService permissionsService;
  @Mock
  FeatureService featureService;
  @Mock
  PermissionVersionRefreshService permissionVersionRefreshService;

  @InjectMocks
  PluginRbacContributionHandler handler;

  @Test
  void validate_acceptsManifestContributionsWithoutWritingResources() {
    final Plugin plugin = plugin();
    final Menu parent = menu("parent-id", "platform.root", "/platform", null);

    when(remoteModuleRepository.findAll(Map.of("serviceName", "analytics"))).thenReturn(List.of());
    when(remoteModuleRepository.findAll(Map.of("entry", "http://cdn.example.com/analytics/mf-manifest.json"))).thenReturn(List.of());
    when(permissionsRepository.findAll(Map.of("authority", "analytics.view"))).thenReturn(List.of());
    when(featureRepository.findAllByCodes(Set.of("analytics.dashboard"))).thenReturn(List.of());
    when(menuRepository.findAll(Map.of())).thenReturn(List.of(parent));

    handler.validate(plugin);

    verify(remoteModuleRepository, never()).save(any(MicroModule.class));
    verify(permissionsRepository, never()).save(any(Permissions.class));
    verify(featureRepository, never()).save(any(Feature.class));
    verifyNoInteractions(microAppService, menuService, permissionsService, featureService);
  }

  @Test
  void validate_rejectsRemoteOwnedByAnotherPlugin() {
    MicroModule existing = new MicroModule();
    existing.setId("remote-id");
    existing.setServiceName("analytics");
    existing.setPluginId("another.plugin");

    when(remoteModuleRepository.findAll(Map.of("serviceName", "analytics"))).thenReturn(List.of(existing));
    when(remoteModuleRepository.findAll(Map.of("entry", "http://cdn.example.com/analytics/mf-manifest.json"))).thenReturn(List.of());

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.validate(plugin()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("frontend remote 'analytics' is not owned by plugin plugin.analytics");
    verify(remoteModuleRepository, never()).save(any(MicroModule.class));
  }

  @Test
  void validateBatch_rejectsDuplicateRemoteAcrossPluginCandidates() {
    Plugin reports = pluginWithRemote(
        "plugin.reports",
        "analytics",
        "http://cdn.example.com/reports/mf-manifest.json");

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.validate(List.of(plugin(), reports)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("frontend remote 'analytics' is declared by multiple plugin candidates");
    verifyNoInteractions(remoteModuleRepository, menuRepository, permissionsRepository, featureRepository);
  }

  @Test
  void validateBatch_acceptsFeaturePermissionFromDependencyCandidateWithoutWritingResources() {
    Plugin permissions = pluginWithPermission("plugin.permissions", "shared.view");
    Plugin feature = pluginWithFeature("plugin.analytics", "analytics.dashboard", "shared.view", "plugin.permissions");

    when(permissionsRepository.findAll(Map.of("authority", "shared.view"))).thenReturn(List.of());
    when(featureRepository.findAllByCodes(Set.of("analytics.dashboard"))).thenReturn(List.of());

    handler.validate(List.of(permissions, feature));

    verify(permissionsRepository, never()).save(any(Permissions.class));
    verify(featureRepository, never()).save(any(Feature.class));
    verifyNoInteractions(microAppService, menuService, permissionsService, featureService);
  }

  @Test
  void installed_registersManifestContributions() {
    final Plugin plugin = plugin();
    final Menu parent = menu("parent-id", "platform.root", "/platform", null);
    Permissions existingPermission = new Permissions();
    existingPermission.setAuthority("analytics.view");

    when(remoteModuleRepository.findAll(Map.of("serviceName", "analytics"))).thenReturn(List.of());
    when(remoteModuleRepository.findAll(Map.of("entry", "http://cdn.example.com/analytics/mf-manifest.json"))).thenReturn(List.of());
    when(permissionsRepository.findAll(Map.of("authority", "analytics.view"))).thenReturn(List.of());
    when(featureRepository.findAllByCodes(Set.of("analytics.dashboard"))).thenReturn(List.of());
    when(permissionsRepository.findAll(Map.of("authority", "in:analytics.view"))).thenReturn(List.of(existingPermission));
    when(featureService.authorizedPermissions("analytics.dashboard")).thenReturn(List.of());
    when(menuRepository.findAll(Map.of())).thenReturn(List.of(parent));
    when(menuService.authorized("menu-id")).thenReturn(List.of());
    doAnswer(invocation -> {
      Menu menu = invocation.getArgument(0);
      menu.setId("menu-id");
      return menu;
    }).when(menuService).initializeMenu(any(Menu.class));

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

    ArgumentCaptor<Permissions> permissionCaptor = ArgumentCaptor.forClass(Permissions.class);
    verify(permissionsRepository).save(permissionCaptor.capture());
    assertThat(permissionCaptor.getValue().getPluginId()).isEqualTo("plugin.analytics");
    assertThat(permissionCaptor.getValue().getResource()).isEqualTo("/analytics/**");

    ArgumentCaptor<Feature> featureCaptor = ArgumentCaptor.forClass(Feature.class);
    verify(featureRepository).save(featureCaptor.capture());
    assertThat(featureCaptor.getValue().getPluginId()).isEqualTo("plugin.analytics");
    assertThat(featureCaptor.getValue().getPublicAccess()).isFalse();

    ArgumentCaptor<FeaturePermissionsRelevanceDto> featurePermissionCaptor =
        ArgumentCaptor.forClass(FeaturePermissionsRelevanceDto.class);
    verify(featureService).initializePermissions(featurePermissionCaptor.capture());
    assertThat(featurePermissionCaptor.getValue().getFeatureCode()).isEqualTo("analytics.dashboard");
    assertThat(featurePermissionCaptor.getValue().getPermissionAuthority()).containsExactly("analytics.view");

    ArgumentCaptor<Menu> menuCaptor = ArgumentCaptor.forClass(Menu.class);
    verify(menuService).initializeMenu(menuCaptor.capture());
    assertThat(menuCaptor.getValue().getPluginId()).isEqualTo("plugin.analytics");
    assertThat(menuCaptor.getValue().getParent()).isEqualTo("parent-id");

    ArgumentCaptor<MenuFeaturesRelevanceDto> menuFeatureCaptor =
        ArgumentCaptor.forClass(MenuFeaturesRelevanceDto.class);
    verify(menuService).authorize(menuFeatureCaptor.capture());
    assertThat(menuFeatureCaptor.getValue().getMenuId()).isEqualTo("menu-id");
    assertThat(menuFeatureCaptor.getValue().resolvedFeatureCodes()).containsExactly("analytics.dashboard");
  }

  @Test
  void installed_cleansOwnedContributionsWhenRegistrationFails() {
    final Plugin plugin = plugin();
    MicroModule remote = new MicroModule();
    remote.setId("remote-id");
    remote.setPluginId("plugin.analytics");

    when(remoteModuleRepository.findAll(Map.of("serviceName", "analytics"))).thenReturn(List.of());
    when(remoteModuleRepository.findAll(Map.of("entry", "http://cdn.example.com/analytics/mf-manifest.json"))).thenReturn(List.of());
    doAnswer(invocation -> {
      MicroModule saved = invocation.getArgument(0);
      saved.setId("remote-id");
      return saved;
    }).when(remoteModuleRepository).save(any(MicroModule.class));
    when(permissionsRepository.findAll(Map.of("authority", "analytics.view"))).thenReturn(List.of());
    doThrow(new IllegalStateException("permission save failed")).when(permissionsRepository)
        .save(any(Permissions.class));
    when(menuRepository.findAll(Map.of("pluginId", "plugin.analytics"))).thenReturn(List.of());
    when(featureRepository.findAll(Map.of("pluginId", "plugin.analytics"))).thenReturn(List.of());
    when(permissionsRepository.findAll(Map.of("pluginId", "plugin.analytics"))).thenReturn(List.of());
    when(remoteModuleRepository.findAll(Map.of("pluginId", "plugin.analytics"))).thenReturn(List.of(remote));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.installed(plugin))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("permission save failed");

    verify(microAppService).removeByIds(Set.of("remote-id"));
    verifyNoInteractions(featureService);
    verifyNoInteractions(menuService);
  }

  @Test
  void uninstalling_removesOwnedResources() {
    final Menu menu = menu("menu-id", "analytics.menu", "/analytics", "plugin.analytics");
    Feature feature = new Feature();
    feature.setId("feature-id");
    feature.setCode("analytics.dashboard");
    feature.setPluginId("plugin.analytics");
    Permissions permission = new Permissions();
    permission.setId("permission-id");
    permission.setPluginId("plugin.analytics");
    MicroModule remote = new MicroModule();
    remote.setId("remote-id");
    remote.setPluginId("plugin.analytics");

    when(menuRepository.findAll(Map.of("pluginId", "plugin.analytics"))).thenReturn(List.of(menu));
    when(featureRepository.findAll(Map.of("pluginId", "plugin.analytics"))).thenReturn(List.of(feature));
    when(permissionsRepository.findAll(Map.of("pluginId", "plugin.analytics"))).thenReturn(List.of(permission));
    when(remoteModuleRepository.findAll(Map.of("pluginId", "plugin.analytics"))).thenReturn(List.of(remote));

    handler.uninstalling(plugin());

    verify(menuService).removeByIds(Set.of("menu-id"));
    verify(featurePermissionRelevanceRepository).deleteAllByFeatureCodes(Set.of("analytics.dashboard"));
    verify(applicationFeatureRelevanceRepository).deleteAllByFeatureCodes(Set.of("analytics.dashboard"));
    verify(featureRepository).deleteByIds(Set.of("feature-id"));
    verify(permissionVersionRefreshService).refreshByFeatureCodes(Set.of("analytics.dashboard"));
    verify(permissionsService).removeByIds(Set.of("permission-id"));
    verify(microAppService).removeByIds(Set.of("remote-id"));
  }

  private static Plugin plugin() {
    PluginManifest manifest = new PluginManifest();
    manifest.setId("plugin.analytics");
    manifest.setName("Analytics");
    manifest.setVersion("1.0.0");

    final PluginManifest.Frontend frontend = new PluginManifest.Frontend();
    final PluginManifest.RemoteContribution remote = new PluginManifest.RemoteContribution();
    remote.setName("analytics");
    remote.setEntry("http://cdn.example.com/analytics/mf-manifest.json");
    remote.setVersion("2.0.0");
    frontend.setRemotes(List.of(remote));
    manifest.setFrontend(frontend);

    PluginManifest.PermissionContribution permission = new PluginManifest.PermissionContribution();
    permission.setAuthority("analytics.view");
    permission.setName("Analytics View");
    permission.setResource("/analytics/**");
    manifest.setPermissions(List.of(permission));

    PluginManifest.FeatureContribution feature = new PluginManifest.FeatureContribution();
    feature.setCode("analytics.dashboard");
    feature.setName("Analytics Dashboard");
    feature.setPermissions(Set.of("analytics.view"));
    manifest.setFeatures(List.of(feature));

    PluginManifest.MenuContribution menu = new PluginManifest.MenuContribution();
    menu.setAuthority("analytics.menu");
    menu.setTitle("Analytics");
    menu.setPath("/analytics");
    menu.setComponent("analytics/Dashboard");
    menu.setParent("platform.root");
    menu.setFeatureCodes(Set.of("analytics.dashboard"));
    manifest.setMenus(List.of(menu));

    PluginArtifact artifact = new PluginArtifact(
        URI.create("file:/plugins/analytics.jar"),
        42,
        "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789");
    return new Plugin(artifact, manifest, Map.of());
  }

  private static Plugin plugin(PluginManifest manifest) {
    PluginArtifact artifact = new PluginArtifact(
        URI.create("file:/plugins/" + manifest.getId() + ".jar"),
        42,
        "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789");
    return new Plugin(artifact, manifest, Map.of());
  }

  private static Plugin pluginWithRemote(String pluginId, String remoteName, String entry) {
    final PluginManifest manifest = baseManifest(pluginId);
    final PluginManifest.Frontend frontend = new PluginManifest.Frontend();
    final PluginManifest.RemoteContribution remote = new PluginManifest.RemoteContribution();
    remote.setName(remoteName);
    remote.setEntry(entry);
    frontend.setRemotes(List.of(remote));
    manifest.setFrontend(frontend);
    return plugin(manifest);
  }

  private static Plugin pluginWithPermission(String pluginId, String authority) {
    PluginManifest manifest = baseManifest(pluginId);
    PluginManifest.PermissionContribution permission = new PluginManifest.PermissionContribution();
    permission.setAuthority(authority);
    permission.setName(authority);
    manifest.setPermissions(List.of(permission));
    return plugin(manifest);
  }

  private static Plugin pluginWithFeature(
      String pluginId,
      String featureCode,
      String permissionAuthority,
      String dependencyId
  ) {
    PluginManifest manifest = baseManifest(pluginId);
    PluginManifest.PluginDependency dependency = new PluginManifest.PluginDependency();
    dependency.setId(dependencyId);
    manifest.setDependencies(List.of(dependency));
    PluginManifest.FeatureContribution feature = new PluginManifest.FeatureContribution();
    feature.setCode(featureCode);
    feature.setName(featureCode);
    feature.setPermissions(Set.of(permissionAuthority));
    manifest.setFeatures(List.of(feature));
    return plugin(manifest);
  }

  private static PluginManifest baseManifest(String pluginId) {
    PluginManifest manifest = new PluginManifest();
    manifest.setId(pluginId);
    manifest.setName(pluginId);
    manifest.setVersion("1.0.0");
    return manifest;
  }

  private static Menu menu(String id, String authority, String path, String pluginId) {
    Menu menu = new Menu();
    menu.setId(id);
    menu.setAuthority(authority);
    menu.setPath(path);
    menu.setPluginId(pluginId);
    return menu;
  }
}
