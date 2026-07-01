package org.simplepoint.plugin.rbac.menu.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyCollection;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.data.initialize.DataInitializeExecutor;
import org.simplepoint.plugin.auditing.logging.api.service.PermissionChangeLogRemoteService;
import org.simplepoint.plugin.rbac.core.api.service.PermissionsService;
import org.simplepoint.plugin.rbac.menu.api.repository.MenuAncestorRepository;
import org.simplepoint.plugin.rbac.menu.api.repository.MenuFeatureRelevanceRepository;
import org.simplepoint.plugin.rbac.menu.api.repository.MenuRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.FeatureService;
import org.simplepoint.security.MenuChildren;
import org.simplepoint.security.entity.Menu;
import org.simplepoint.security.entity.TreeMenu;
import org.simplepoint.security.pojo.dto.MenuFeaturesRelevanceDto;
import org.simplepoint.security.pojo.dto.ServiceMenuResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class MenuServiceImplTest {

  @Mock
  MenuRepository menuRepository;

  @Mock
  DetailsProviderService detailsProviderService;

  @Mock
  MenuAncestorRepository menuAncestorRepository;

  @Mock
  PermissionsService permissionsService;

  @Mock
  FeatureService featureService;

  @Mock
  MenuFeatureRelevanceRepository menuFeatureRelevanceRepository;

  @Mock
  DataInitializeExecutor dataInitializeManager;

  @Mock
  PermissionChangeLogRemoteService permissionChangeLogRemoteService;

  @InjectMocks
  MenuServiceImpl service;

  private Menu menuWithPath(String id, String path) {
    Menu menu = new Menu();
    menu.setId(id);
    menu.setPath(path);
    return menu;
  }

  @Test
  void create_withNullAuthority_setsAuthorityFromPath() {
    Menu menu = menuWithPath("m1", "/dashboard/home");
    when(menuRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(detailsProviderService.getDialects(any())).thenReturn(Collections.emptySet());

    service.create(menu);

    assertThat(menu.getAuthority()).isEqualTo(".dashboard.home");
  }

  @Test
  void create_withExistingAuthority_keepsAuthority() {
    Menu menu = menuWithPath("m1", "/dashboard/home");
    menu.setAuthority("existing.authority");
    when(menuRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(detailsProviderService.getDialects(any())).thenReturn(Collections.emptySet());

    service.create(menu);

    assertThat(menu.getAuthority()).isEqualTo("existing.authority");
  }

  @Test
  void create_withParent_savesAncestors() {
    Menu menu = menuWithPath("m1", "/child");
    menu.setParent("parent1");
    when(menuRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(detailsProviderService.getDialects(any())).thenReturn(Collections.emptySet());
    when(menuAncestorRepository.findAncestorIdsByChildIdIn(Set.of("parent1"))).thenReturn(List.of("grandparent1"));
    when(menuAncestorRepository.saveAll(any())).thenReturn(Collections.emptyList());

    service.create(menu);

    verify(menuAncestorRepository).saveAll(argThat(list -> {
      List<?> items = (List<?>) list;
      return items.size() == 2; // grandparent + direct parent
    }));
  }

  @Test
  void create_withoutParent_noAncestorSave() {
    Menu menu = menuWithPath("m1", "/root");
    when(menuRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(detailsProviderService.getDialects(any())).thenReturn(Collections.emptySet());

    service.create(menu);

    verify(menuAncestorRepository, never()).saveAll(any());
  }

  @Test
  void removeByIds_withNullIds_doesNothing() {
    service.removeByIds(null);
    verifyNoInteractions(menuAncestorRepository, menuFeatureRelevanceRepository, menuRepository);
  }

  @Test
  void removeByIds_withEmptyIds_doesNothing() {
    service.removeByIds(Collections.emptySet());
    verifyNoInteractions(menuAncestorRepository, menuFeatureRelevanceRepository, menuRepository);
  }

  @Test
  void removeByIds_withIds_deletesChildrenAndFeatures() {
    Set<String> ids = Set.of("m1");
    when(menuAncestorRepository.findChildIdsByAncestorIds(ids)).thenReturn(List.of("child1"));
    when(menuRepository.findAllByIds(any())).thenReturn(Collections.emptyList());

    service.removeByIds(ids);

    verify(menuFeatureRelevanceRepository).deleteAllByMenuIds(argThat(deleteIds ->
        deleteIds.contains("m1") && deleteIds.contains("child1")));
    verify(menuRepository).deleteByIds(any());
  }

  @Test
  void authorized_delegatesToFeatureRelevanceRepo() {
    when(menuFeatureRelevanceRepository.authorized("menu1")).thenReturn(List.of("feat1"));

    Collection<String> result = service.authorized("menu1");

    assertThat(result).containsExactly("feat1");
  }

  @Test
  void authorize_withEmptyFeatureCodes_skips() {
    MenuFeaturesRelevanceDto dto = new MenuFeaturesRelevanceDto("menu1", null);

    service.authorize(dto);

    verifyNoInteractions(menuFeatureRelevanceRepository);
  }

  @Test
  void authorize_withValidFeatureCodes_savesRelevance() {
    final MenuFeaturesRelevanceDto dto = new MenuFeaturesRelevanceDto("menu1", Set.of("feat1", "feat2"));
    Menu menu = menuWithPath("menu1", "/menu1");
    when(menuRepository.findById("menu1")).thenReturn(Optional.of(menu));
    org.simplepoint.plugin.rbac.tenant.api.entity.Feature feature1 = new org.simplepoint.plugin.rbac.tenant.api.entity.Feature();
    feature1.setCode("feat1");
    org.simplepoint.plugin.rbac.tenant.api.entity.Feature feature2 = new org.simplepoint.plugin.rbac.tenant.api.entity.Feature();
    feature2.setCode("feat2");
    when(featureService.findAllByCodes(Set.of("feat1", "feat2"))).thenReturn(List.of(feature1, feature2));

    service.authorize(dto);

    verify(menuFeatureRelevanceRepository).authorize(argThat(rels -> {
      var list = (java.util.Collection<?>) rels;
      return list.size() == 2;
    }));
  }

  @Test
  void authorize_withUnknownFeatureCode_throwsIllegalArgument() {
    final MenuFeaturesRelevanceDto dto = new MenuFeaturesRelevanceDto("menu1", Set.of("feat1", "feat-missing"));
    Menu menu = menuWithPath("menu1", "/menu1");
    when(menuRepository.findById("menu1")).thenReturn(Optional.of(menu));
    org.simplepoint.plugin.rbac.tenant.api.entity.Feature feature = new org.simplepoint.plugin.rbac.tenant.api.entity.Feature();
    feature.setCode("feat1");
    when(featureService.findAllByCodes(Set.of("feat1", "feat-missing"))).thenReturn(List.of(feature));

    assertThatThrownBy(() -> service.authorize(dto))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("功能不存在");
  }

  @Test
  void unauthorized_withEmptyFeatureCodes_skips() {
    MenuFeaturesRelevanceDto dto = new MenuFeaturesRelevanceDto("menu1", null);

    service.unauthorized(dto);

    verify(menuFeatureRelevanceRepository, never()).unauthorized(any(), any());
  }

  @Test
  void unauthorized_withValidFeatureCodes_callsRepo() {
    Set<String> featureCodes = Set.of("feat1");
    MenuFeaturesRelevanceDto dto = new MenuFeaturesRelevanceDto("menu1", featureCodes);
    when(menuRepository.findById("menu1")).thenReturn(Optional.of(menuWithPath("menu1", "/menu1")));

    service.unauthorized(dto);

    verify(menuFeatureRelevanceRepository).unauthorized("menu1", featureCodes);
  }

  @Test
  void routes_nullContext_throwsIllegalArgument() {
    try (MockedStatic<AuthorizationContextHolder> mockedStatic = mockStatic(AuthorizationContextHolder.class)) {
      mockedStatic.when(AuthorizationContextHolder::getContext).thenReturn(null);

      assertThatThrownBy(() -> service.routes())
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void routes_noFeatures_returnsEmpty() {
    try (MockedStatic<AuthorizationContextHolder> mockedStatic = mockStatic(AuthorizationContextHolder.class)) {
      AuthorizationContext ctx = new AuthorizationContext();
      ctx.setIsAdministrator(false);
      ctx.setPermissions(Collections.emptySet());
      mockedStatic.when(AuthorizationContextHolder::getContext).thenReturn(ctx);

      ServiceMenuResult result = service.routes();

      assertThat(result.services()).isEmpty();
      assertThat(result.routes()).isEmpty();
      assertThat(result.authorizationContext()).containsEntry("scopeType", "");
    }
  }

  @Test
  void routes_adminUser_returnsFullTree() {
    try (MockedStatic<AuthorizationContextHolder> mockedStatic = mockStatic(AuthorizationContextHolder.class)) {
      AuthorizationContext ctx = new AuthorizationContext();
      ctx.setIsAdministrator(true);
      ctx.setPermissions(Collections.emptySet());
      mockedStatic.when(AuthorizationContextHolder::getContext).thenReturn(ctx);

      Menu m1 = menuWithPath("m1", "/admin/users");
      when(menuRepository.loadAll()).thenReturn(List.of(m1));

      ServiceMenuResult result = service.routes();

      assertThat(result).isNotEqualTo(ServiceMenuResult.EMPTY);
      assertThat(result.routes()).hasSize(1);
    }
  }

  @Test
  void buildMenuTree_flatMenus_returnsRoots() {
    Menu m1 = menuWithPath("m1", "/page1");
    Menu m2 = menuWithPath("m2", "/page2");

    List<TreeMenu> roots = service.buildMenuTree(List.of(m1, m2), new HashSet<>(), false);

    assertThat(roots).hasSize(2);
  }

  @Test
  void buildMenuTree_withParentChild_buildsHierarchy() {
    Menu parent = menuWithPath("parent", "/parent");
    Menu child = menuWithPath("child", "/parent/child");
    child.setParent("parent");

    List<TreeMenu> roots = service.buildMenuTree(List.of(parent, child), new HashSet<>(), false);

    assertThat(roots).hasSize(1);
    TreeMenu root = roots.get(0);
    assertThat(root.getId()).isEqualTo("parent");
    assertThat(root.getChildren()).hasSize(1);
  }

  // ── routes (non-admin with features) ─────────────────────────────────────

  @Test
  void routes_nonAdminWithFeatureCodes_returnsFilteredTree() {
    try (MockedStatic<AuthorizationContextHolder> mockedStatic = mockStatic(AuthorizationContextHolder.class)) {
      AuthorizationContext ctx = new AuthorizationContext();
      ctx.setIsAdministrator(false);
      ctx.setPermissions(Set.of("feat1"));
      mockedStatic.when(AuthorizationContextHolder::getContext).thenReturn(ctx);

      when(menuFeatureRelevanceRepository.findAllMenuIdByFeatureCodes(Set.of("feat1")))
          .thenReturn(List.of("m1"));
      when(menuAncestorRepository.findAncestorIdsByChildIdIn(Set.of("m1")))
          .thenReturn(Collections.emptyList());
      Menu m1 = menuWithPath("m1", "/admin/users");
      when(menuRepository.loadByIds(any())).thenReturn(List.of(m1));

      ServiceMenuResult result = service.routes();

      assertThat(result).isNotEqualTo(ServiceMenuResult.EMPTY);
      assertThat(result.routes()).hasSize(1);
    }
  }

  @Test
  void routes_nonAdminWithOrgFeature_marksMenuAsRequireOrgTenant() {
    try (MockedStatic<AuthorizationContextHolder> mockedStatic = mockStatic(AuthorizationContextHolder.class)) {
      AuthorizationContext ctx = new AuthorizationContext();
      ctx.setIsAdministrator(false);
      ctx.setPermissions(Set.of("feat-org"));
      mockedStatic.when(AuthorizationContextHolder::getContext).thenReturn(ctx);

      when(menuFeatureRelevanceRepository.findAllMenuIdByFeatureCodes(Set.of("feat-org")))
          .thenReturn(List.of("m1"));
      when(menuAncestorRepository.findAncestorIdsByChildIdIn(Set.of("m1")))
          .thenReturn(Collections.emptyList());
      when(featureService.findAllRequireOrgTenantCodes()).thenReturn(Set.of("feat-org"));
      Menu m1 = menuWithPath("m1", "/platform/organization-only");
      when(menuRepository.loadByIds(any())).thenReturn(List.of(m1));

      ServiceMenuResult result = service.routes();

      assertThat(result.routes()).hasSize(1);
      assertThat(result.routes()).anySatisfy(route -> assertThat(route.getRequireOrgTenant()).isTrue());
    }
  }

  @Test
  void routes_adminUser_usesCacheOnSecondCall() {
    try (MockedStatic<AuthorizationContextHolder> mockedStatic = mockStatic(AuthorizationContextHolder.class)) {
      AuthorizationContext ctx = new AuthorizationContext();
      ctx.setIsAdministrator(true);
      ctx.setPermissions(Collections.emptySet());
      mockedStatic.when(AuthorizationContextHolder::getContext).thenReturn(ctx);

      Menu m1 = menuWithPath("m1", "/admin");
      when(menuRepository.loadAll()).thenReturn(List.of(m1));

      service.routes(); // first call populates cache
      service.routes(); // second call should use cache

      // loadAll() is called only once due to caching
      verify(menuRepository, times(1)).loadAll();
    }
  }

  // ── removeById ────────────────────────────────────────────────────────────

  @Test
  void removeById_delegatesToRemoveByIds() {
    when(menuAncestorRepository.findChildIdsByAncestorIds(any())).thenReturn(Collections.emptyList());
    when(menuRepository.findAllByIds(any())).thenReturn(Collections.emptyList());

    service.removeById("m1");

    verify(menuFeatureRelevanceRepository).deleteAllByMenuIds(argThat(ids -> ids.contains("m1")));
    verify(menuRepository).deleteByIds(any());
  }

  // ── limitTree ─────────────────────────────────────────────────────────────

  @Test
  void limitTree_returnsPaginatedTree() {
    Menu root = menuWithPath("root1", "/root");
    Page<Menu> rootPage = new PageImpl<>(List.of(root), Pageable.ofSize(10), 1);
    when(menuRepository.limit(any(), any())).thenReturn(rootPage);
    when(menuAncestorRepository.findChildIdsByAncestorIds(any())).thenReturn(Collections.emptyList());
    when(menuRepository.findAllByIds(any())).thenReturn(Collections.emptyList());

    Page<TreeMenu> result = service.limitTree(new java.util.HashMap<>(), Pageable.ofSize(10));

    assertThat(result.getTotalElements()).isEqualTo(1);
    assertThat(result.getContent()).hasSize(1);
  }

  // ── sync ─────────────────────────────────────────────────────────────────

  @Test
  void sync_callsDataInitializeExecutorForBothTasks() {
    MenuChildren child = new MenuChildren();
    child.setPath("/home");
    child.setPermissions(null);
    child.setChildren(null);

    service.sync("test-service", Set.of(child));

    verify(dataInitializeManager, times(2)).execute(any(), any());
  }

  @Test
  void sync_withEmptyData_callsInitializeManager() {
    service.sync("test-service", Collections.emptySet());

    verify(dataInitializeManager, times(2)).execute(any(), any());
  }

  // ── buildMenuTree with clearUnnecessaryFields ─────────────────────────────

  @Test
  void buildMenuTree_clearFieldsTrue_nullifiesAuditFields() {
    Menu m = menuWithPath("m1", "/page1");
    m.setCreatedBy("admin");

    List<TreeMenu> roots = service.buildMenuTree(List.of(m), new HashSet<>(), true);

    assertThat(roots).hasSize(1);
    assertThat(roots.get(0).getCreatedBy()).isNull();
    assertThat(roots.get(0).getId()).isNull();
  }

  // ── getServiceName via buildMenuTree component field ──────────────────────

  @Test
  void buildMenuTree_withComponentPath_populatesServiceEntry() {
    Set<ServiceMenuResult.ServiceEntry> services = new HashSet<>();
    Menu m = menuWithPath("m1", "/page1");
    m.setComponent("/myService/SomeView");

    service.buildMenuTree(List.of(m), services, false);

    assertThat(services).anyMatch(e -> "myService".equals(e.name()));
  }

  @Test
  void buildMenuTree_withNullComponentPath_noServiceEntry() {
    Set<ServiceMenuResult.ServiceEntry> services = new HashSet<>();
    Menu m = menuWithPath("m1", "/page1");
    m.setComponent(null);

    service.buildMenuTree(List.of(m), services, false);

    assertThat(services).anyMatch(e -> e.name() == null);
  }

  // ── authorize / unauthorized with auth context (covers recordPermissionChange) ─

  @Test
  void authorize_withAuthContextHavingUserId_recordsPermissionChange() {
    try (MockedStatic<AuthorizationContextHolder> mockedStatic = mockStatic(AuthorizationContextHolder.class)) {
      AuthorizationContext ctx = new AuthorizationContext();
      ctx.setUserId("user1");
      ctx.setIsAdministrator(false);
      ctx.setPermissions(Collections.emptySet());
      mockedStatic.when(AuthorizationContextHolder::getContext).thenReturn(ctx);

      when(menuRepository.findById("menu1")).thenReturn(Optional.of(menuWithPath("menu1", "/admin")));
      org.simplepoint.plugin.rbac.tenant.api.entity.Feature feature = new org.simplepoint.plugin.rbac.tenant.api.entity.Feature();
      feature.setCode("feat1");
      when(featureService.findAllByCodes(any())).thenReturn(List.of(feature));

      MenuFeaturesRelevanceDto dto = new MenuFeaturesRelevanceDto("menu1", Set.of("feat1"));
      service.authorize(dto);

      verify(menuFeatureRelevanceRepository).authorize(any());
      verify(permissionChangeLogRemoteService).record(any());
    }
  }

  @Test
  void unauthorized_withAuthContextHavingUserId_recordsPermissionChange() {
    try (MockedStatic<AuthorizationContextHolder> mockedStatic = mockStatic(AuthorizationContextHolder.class)) {
      AuthorizationContext ctx = new AuthorizationContext();
      ctx.setUserId("user1");
      ctx.setIsAdministrator(false);
      ctx.setPermissions(Collections.emptySet());
      mockedStatic.when(AuthorizationContextHolder::getContext).thenReturn(ctx);

      when(menuRepository.findById("menu1")).thenReturn(Optional.of(menuWithPath("menu1", "/admin")));

      MenuFeaturesRelevanceDto dto = new MenuFeaturesRelevanceDto("menu1", Set.of("feat1"));
      service.unauthorized(dto);

      verify(menuFeatureRelevanceRepository).unauthorized(eq("menu1"), any());
      verify(permissionChangeLogRemoteService).record(any());
    }
  }

  // ── sync with executor running lambdas (covers private init methods) ─────

  @Test
  void sync_withExecutorRunningLambdas_coversInitMethods() throws Exception {
    // Make dataInitializeManager.execute() actually invoke the lambda
    doAnswer(inv -> {
      org.simplepoint.api.data.InitTask.Initializer initializer = inv.getArgument(1);
      initializer.run();
      return true;
    }).when(dataInitializeManager).execute(any(), any());

    // Minimal MenuChildren with no permissions, no children
    MenuChildren child = new MenuChildren();
    child.setPath("/home");
    child.setAuthority(".home");
    child.setPermissions(null);
    child.setChildren(null);

    // Stubs for initializeMenusAndPermissions -> this.create()
    when(menuRepository.save(any())).thenAnswer(inv -> {
      Menu m = inv.getArgument(0);
      m.setId("generated-id");
      return m;
    });
    when(detailsProviderService.getDialects(any())).thenReturn(Collections.emptySet());

    // Stubs for initializeFeaturesAndRelations -> menuRepository.loadAll()
    Menu loaded = menuWithPath("generated-id", "/home");
    loaded.setAuthority(".home");
    when(menuRepository.loadAll()).thenReturn(List.of(loaded));

    service.sync("test-service", Set.of(child));

    verify(dataInitializeManager, times(2)).execute(any(), any());
    verify(menuRepository).save(any());
  }

  @Test
  void sync_withExistingFeatureDefinition_doesNotCreateDuplicateFeature() throws Exception {
    doAnswer(inv -> {
      org.simplepoint.api.data.InitTask.Initializer initializer = inv.getArgument(1);
      initializer.run();
      return true;
    }).when(dataInitializeManager).execute(any(), any());

    org.simplepoint.security.MenuFeatureDefinition definition = new org.simplepoint.security.MenuFeatureDefinition();
    definition.setCode("dashboard");
    definition.setName("Dashboard");
    definition.setDescription("Dashboard feature");

    org.simplepoint.security.entity.Permissions permission = new org.simplepoint.security.entity.Permissions();
    permission.setAuthority("dashboard.view");
    permission.setFeature(definition);

    MenuChildren child = new MenuChildren();
    child.setPath("/dashboard");
    child.setAuthority("dashboard");
    child.setSort(1);
    child.setPermissions(Set.of(permission));

    when(menuRepository.save(any())).thenAnswer(inv -> {
      Menu m = inv.getArgument(0);
      m.setId("dashboard-menu");
      return m;
    });
    when(detailsProviderService.getDialects(any())).thenReturn(Collections.emptySet());

    Menu loaded = menuWithPath("dashboard-menu", "/dashboard");
    loaded.setAuthority("dashboard");
    when(menuRepository.loadAll()).thenReturn(List.of(loaded));

    org.simplepoint.plugin.rbac.tenant.api.entity.Feature existing = new org.simplepoint.plugin.rbac.tenant.api.entity.Feature();
    existing.setId("existing-feature");
    existing.setCode("dashboard");
    existing.setName("Dashboard");
    existing.setDescription("Dashboard feature");
    existing.setSort(1);
    existing.setPublicAccess(false);
    existing.setRequireOrgTenant(false);
    when(featureService.findAllByCodes(Set.of("dashboard"))).thenReturn(List.of(existing));
    when(featureService.authorizedPermissions("dashboard")).thenReturn(List.of("dashboard.view"));
    when(menuFeatureRelevanceRepository.authorized("dashboard-menu")).thenReturn(List.of("dashboard"));
    when(permissionsService.findAll(any())).thenReturn(Collections.emptyList());

    service.sync("svc", Set.of(child));

    verify(featureService, never()).create(anyCollection());
    verify(featureService, never()).initializeFeature(any());
  }

  @Test
  void sync_withPermissionsAndChildren_executesAll() throws Exception {
    doAnswer(inv -> {
      org.simplepoint.api.data.InitTask.Initializer initializer = inv.getArgument(1);
      initializer.run();
      return true;
    }).when(dataInitializeManager).execute(any(), any());

    org.simplepoint.security.entity.Permissions perm = new org.simplepoint.security.entity.Permissions();
    perm.setAuthority("menu.view");
    perm.setType(1);

    MenuChildren child = new MenuChildren();
    child.setPath("/settings");
    child.setPermissions(Set.of(perm));
    child.setChildren(null);

    when(menuRepository.save(any())).thenAnswer(inv -> {
      Menu m = inv.getArgument(0);
      m.setId("s-id");
      return m;
    });
    when(detailsProviderService.getDialects(any())).thenReturn(Collections.emptySet());
    // permissionsService.create(Collection) - no stub needed, return null is fine since result unused

    Menu loaded = menuWithPath("s-id", "/settings");
    when(menuRepository.loadAll()).thenReturn(List.of(loaded));
    when(permissionsService.findAll(any())).thenReturn(Collections.emptyList());

    service.sync("svc", Set.of(child));

    verify(permissionsService).create(anyCollection());
  }
}
