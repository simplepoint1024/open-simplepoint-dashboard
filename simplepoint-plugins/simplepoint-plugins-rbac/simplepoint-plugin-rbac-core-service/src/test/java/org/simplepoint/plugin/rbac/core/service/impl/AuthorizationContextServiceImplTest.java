package org.simplepoint.plugin.rbac.core.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.authority.RoleGrantedAuthority;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.plugin.rbac.tenant.api.repository.FeaturePermissionRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.security.entity.User;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthorizationContextServiceImplTest {

    @Mock
    UsersService usersService;

    @Mock
    ObjectProvider<FeaturePermissionRelevanceRepository> featurePermissionRelevanceRepositoryProvider;

    @Mock
    ObjectProvider<TenantPackageRelevanceRepository> tenantPackageRelevanceRepositoryProvider;

    @Mock
    ObjectProvider<TenantRepository> tenantRepositoryProvider;

    // Manual construction ensures the correct mock is passed to each constructor parameter
    // (Mockito @InjectMocks cannot distinguish multiple ObjectProvider<?> mocks without -parameters flag)
    AuthorizationContextServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuthorizationContextServiceImpl(
                usersService,
                featurePermissionRelevanceRepositoryProvider,
                tenantPackageRelevanceRepositoryProvider,
                tenantRepositoryProvider
        );
    }

    @Test
    void calculate_userNotFound_throwsRuntimeException() {
        when(usersService.findById("u1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.calculate(null, "u1", null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("用户不存在");
    }

    @Test
    void calculate_defaultTenant_skipsTenantCheck_returnsContext() {
        User user = new User();
        user.setSuperAdmin(false);
        when(usersService.findById("u1")).thenReturn(Optional.of(user));
        when(usersService.loadRolesByUserId("default", "u1")).thenReturn(List.of());
        when(featurePermissionRelevanceRepositoryProvider.getIfAvailable()).thenReturn(null);
        when(tenantPackageRelevanceRepositoryProvider.getIfAvailable()).thenReturn(null);

        AuthorizationContext ctx = service.calculate("default", "u1", "ctx1", null);

        assertThat(ctx).isNotNull();
        assertThat(ctx.getUserId()).isEqualTo("u1");
        assertThat(ctx.getContextId()).isEqualTo("ctx1");
        verifyNoInteractions(tenantRepositoryProvider);
    }

    @Test
    void calculate_superAdmin_skipsTenantCheck() {
        User user = new User();
        user.setSuperAdmin(true);
        when(usersService.findById("u1")).thenReturn(Optional.of(user));
        when(usersService.loadRolesByUserId("tenant1", "u1")).thenReturn(List.of());
        when(featurePermissionRelevanceRepositoryProvider.getIfAvailable()).thenReturn(null);
        when(tenantPackageRelevanceRepositoryProvider.getIfAvailable()).thenReturn(null);

        AuthorizationContext ctx = service.calculate("tenant1", "u1", null, null);

        assertThat(ctx.getIsAdministrator()).isEqualTo(Boolean.TRUE);
        verifyNoInteractions(tenantRepositoryProvider);
    }

    @Test
    void calculate_tenantUser_notInTenant_throwsAccessDeniedException() {
        User user = new User();
        user.setSuperAdmin(false);
        TenantRepository tenantRepository = mock(TenantRepository.class);
        Tenant tenant = new Tenant();
        tenant.setOwnerId("other");

        when(usersService.findById("u1")).thenReturn(Optional.of(user));
        when(tenantRepositoryProvider.getIfAvailable()).thenReturn(tenantRepository);
        when(tenantRepository.findById("tenant1")).thenReturn(Optional.of(tenant));
        when(tenantRepository.hasUser("tenant1", "u1")).thenReturn(false);

        assertThatThrownBy(() -> service.calculate("tenant1", "u1", null, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void calculate_tenantOwner_addsPackageAndFeaturePermissions() {
        User user = new User();
        user.setSuperAdmin(false);
        TenantRepository tenantRepository = mock(TenantRepository.class);
        Tenant tenant = new Tenant();
        tenant.setOwnerId("u1"); // user is owner

        TenantPackageRelevanceRepository tenantPackageRepo = mock(TenantPackageRelevanceRepository.class);
        FeaturePermissionRelevanceRepository featurePermRepo = mock(FeaturePermissionRelevanceRepository.class);

        when(usersService.findById("u1")).thenReturn(Optional.of(user));
        when(tenantRepositoryProvider.getIfAvailable()).thenReturn(tenantRepository);
        when(tenantRepository.findById("tenant1")).thenReturn(Optional.of(tenant));
        when(tenantRepository.hasUser("tenant1", "u1")).thenReturn(true);
        when(usersService.loadRolesByUserId("tenant1", "u1")).thenReturn(List.of());
        when(tenantPackageRelevanceRepositoryProvider.getIfAvailable()).thenReturn(tenantPackageRepo);
        when(featurePermissionRelevanceRepositoryProvider.getIfAvailable()).thenReturn(featurePermRepo);
        when(tenantPackageRepo.findFeatureCodesByTenantId("tenant1")).thenReturn(Set.of("feature1"));
        when(featurePermRepo.findPermissionAuthoritiesByTenantId("tenant1")).thenReturn(Set.of("perm.tenant1"));

        AuthorizationContext ctx = service.calculate("tenant1", "u1", null, null);

        assertThat(ctx.getPermissions()).contains("feature1", "perm.tenant1");
        verify(tenantPackageRepo).findFeatureCodesByTenantId("tenant1");
        verify(featurePermRepo).findPermissionAuthoritiesByTenantId("tenant1");
    }

    @Test
    void calculate_withRoles_loadsPermissions() {
        User user = new User();
        user.setSuperAdmin(false);
        RoleGrantedAuthority role = new RoleGrantedAuthority("role1", "ROLE_USER");

        when(usersService.findById("u1")).thenReturn(Optional.of(user));
        when(usersService.loadRolesByUserId(null, "u1")).thenReturn(List.of(role));
        when(usersService.loadPermissionsInRoleIds(List.of("role1"))).thenReturn(Set.of("perm1", "perm2"));
        when(featurePermissionRelevanceRepositoryProvider.getIfAvailable()).thenReturn(null);
        when(tenantPackageRelevanceRepositoryProvider.getIfAvailable()).thenReturn(null);

        AuthorizationContext ctx = service.calculate(null, "u1", null, null);

        assertThat(ctx.getPermissions()).contains("perm1", "perm2");
        assertThat(ctx.getRoles()).contains("ROLE_USER");
    }
}
