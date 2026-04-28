package org.simplepoint.plugin.rbac.menu.service.impl;

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
import org.simplepoint.security.entity.Menu;
import org.simplepoint.security.entity.TreeMenu;
import org.simplepoint.security.pojo.dto.MenuFeaturesRelevanceDto;
import org.simplepoint.security.pojo.dto.ServiceMenuResult;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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
        MenuFeaturesRelevanceDto dto = new MenuFeaturesRelevanceDto("menu1", Set.of("feat1", "feat2"));

        service.authorize(dto);

        verify(menuFeatureRelevanceRepository).authorize(argThat(rels -> {
            var list = (java.util.Collection<?>) rels;
            return list.size() == 2;
        }));
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

            assertThat(result).isEqualTo(ServiceMenuResult.EMPTY);
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
}
