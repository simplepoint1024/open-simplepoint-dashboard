package org.simplepoint.plugin.rbac.core.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.core.AuthorizationActorRole;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationResourceNamespaces;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.authority.RoleGrantedAuthority;
import org.simplepoint.plugin.rbac.core.api.repository.DataScopeRepository;
import org.simplepoint.plugin.rbac.core.api.repository.FieldScopeRepository;
import org.simplepoint.plugin.rbac.core.api.repository.RoleResourceGrantRepository;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.repository.OrganizationRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.security.entity.DataScope;
import org.simplepoint.security.entity.DataScopeType;
import org.simplepoint.security.entity.RoleResourceGrant;
import org.simplepoint.security.entity.User;
import org.simplepoint.security.service.ResourceService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class AuthorizationContextServiceImplTest {

  @Mock
  UsersService usersService;

  @Mock
  ObjectProvider<ResourceService> resourceServiceProvider;

  @Mock
  ObjectProvider<TenantPackageRelevanceRepository> tenantPackageRelevanceRepositoryProvider;

  @Mock
  ObjectProvider<TenantRepository> tenantRepositoryProvider;

  @Mock
  ObjectProvider<RoleResourceGrantRepository> roleResourceGrantRepositoryProvider;

  @Mock
  ObjectProvider<DataScopeRepository> dataScopeRepositoryProvider;

  @Mock
  ObjectProvider<FieldScopeRepository> fieldScopeRepositoryProvider;

  @Mock
  ObjectProvider<OrganizationRepository> organizationRepositoryProvider;

  AuthorizationContextServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new AuthorizationContextServiceImpl(
        usersService,
        resourceServiceProvider,
        tenantPackageRelevanceRepositoryProvider,
        tenantRepositoryProvider,
        roleResourceGrantRepositoryProvider,
        dataScopeRepositoryProvider,
        fieldScopeRepositoryProvider,
        organizationRepositoryProvider
    );
  }

  @Test
  void calculate_userNotFound_throwsRuntimeException() {
    when(usersService.findByIdForAuthorization("u1")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.calculate(null, "u1", null, null))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("用户不存在");
  }

  @Test
  void calculate_defaultTenantString_treatedAsNull() {
    User user = new User();
    user.setSuperAdmin(false);
    when(usersService.findByIdForAuthorization("u1")).thenReturn(Optional.of(user));
    when(usersService.loadRolesByUserId(null, "u1")).thenReturn(List.of());

    AuthorizationContext ctx = service.calculate("default", "u1", "ctx1", null);

    assertThat(ctx.getUserId()).isEqualTo("u1");
    assertThat(ctx.getContextId()).isEqualTo("ctx1");
    assertThat(ctx.getScopeType()).isEqualTo(AuthorizationScopeType.PERSONAL);
    assertThat(ctx.getActorRole()).isEqualTo(AuthorizationActorRole.PERSONAL_MEMBER);
    assertThat(ctx.getAttribute("X-Tenant-Id")).isNull();
  }

  @Test
  void calculate_superAdminWithoutTenant_usesPlatformScope() {
    User user = new User();
    user.setSuperAdmin(true);
    when(usersService.findByIdForAuthorization("admin")).thenReturn(Optional.of(user));
    when(usersService.loadRolesByUserId(null, "admin")).thenReturn(List.of());

    AuthorizationContext ctx = service.calculate(null, "admin", "ctx-admin", null);

    assertThat(ctx.getIsAdministrator()).isTrue();
    assertThat(ctx.getScopeType()).isEqualTo(AuthorizationScopeType.PLATFORM);
    assertThat(ctx.getActorRole()).isEqualTo(AuthorizationActorRole.PLATFORM_ADMIN);
    assertThat(ctx.getAttribute("X-Scope-Type")).isEqualTo("PLATFORM");
    assertThat(ctx.getAttribute("X-Actor-Role")).isEqualTo("PLATFORM_ADMIN");
  }

  @Test
  void calculate_userWithoutTenant_usesPersonalTenantWhenAvailable() {
    User user = new User();
    user.setSuperAdmin(false);
    TenantRepository tenantRepository = mock(TenantRepository.class);
    Tenant personalTenant = new Tenant();
    personalTenant.setId("personal-1");
    personalTenant.setOwnerId("u1");
    personalTenant.setTenantType(TenantType.PERSONAL);

    when(usersService.findByIdForAuthorization("u1")).thenReturn(Optional.of(user));
    when(tenantRepositoryProvider.getIfAvailable()).thenReturn(tenantRepository);
    when(tenantRepository.findPersonalTenantByOwnerId("u1")).thenReturn(Optional.of(personalTenant));
    when(tenantRepository.hasUser("personal-1", "u1")).thenReturn(true);
    when(usersService.loadRolesByUserId("personal-1", "u1")).thenReturn(List.of());
    when(usersService.loadRolesByUserId(null, "u1")).thenReturn(List.of());

    AuthorizationContext ctx = service.calculate(null, "u1", "ctx1", null);

    assertThat(ctx.getScopeType()).isEqualTo(AuthorizationScopeType.PERSONAL);
    assertThat(ctx.getActorRole()).isEqualTo(AuthorizationActorRole.PERSONAL_OWNER);
    assertThat(ctx.getAttribute("X-Tenant-Id")).isEqualTo("personal-1");
  }

  @Test
  void calculate_tenantUser_notInTenant_throwsAccessDeniedException() {
    User user = new User();
    user.setSuperAdmin(false);
    TenantRepository tenantRepository = mock(TenantRepository.class);
    Tenant tenant = new Tenant();
    tenant.setOwnerId("other");

    when(usersService.findByIdForAuthorization("u1")).thenReturn(Optional.of(user));
    when(tenantRepositoryProvider.getIfAvailable()).thenReturn(tenantRepository);
    when(tenantRepository.findById("tenant1")).thenReturn(Optional.of(tenant));
    when(tenantRepository.hasUser("tenant1", "u1")).thenReturn(false);

    assertThatThrownBy(() -> service.calculate("tenant1", "u1", null, null))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void calculate_tenantOwner_addsPackageAndPublicResources() {
    User user = new User();
    user.setSuperAdmin(false);
    TenantRepository tenantRepository = mock(TenantRepository.class);
    TenantPackageRelevanceRepository tenantPackageRepo = mock(TenantPackageRelevanceRepository.class);
    ResourceService resourceService = mock(ResourceService.class);
    Tenant tenant = new Tenant();
    tenant.setId("tenant1");
    tenant.setOwnerId("u1");
    tenant.setTenantType(TenantType.ORGANIZATION);

    when(usersService.findByIdForAuthorization("u1")).thenReturn(Optional.of(user));
    when(tenantRepositoryProvider.getIfAvailable()).thenReturn(tenantRepository);
    when(tenantRepository.findById("tenant1")).thenReturn(Optional.of(tenant));
    when(tenantRepository.hasUser("tenant1", "u1")).thenReturn(true);
    when(tenantRepository.getTenantAuthorizationVersion("tenant1")).thenReturn(7L);
    when(usersService.loadRolesByUserId("tenant1", "u1")).thenReturn(List.of());
    when(usersService.loadRolesByUserId(null, "u1")).thenReturn(List.of());
    when(resourceServiceProvider.getIfAvailable()).thenReturn(resourceService);
    when(resourceService.findPublicAccessCodes()).thenReturn(Set.of("public.view"));
    when(tenantPackageRelevanceRepositoryProvider.getIfAvailable()).thenReturn(tenantPackageRepo);
    when(tenantPackageRepo.findResourceCodesByTenantId("tenant1")).thenReturn(Set.of("package.resource"));

    AuthorizationContext ctx = service.calculate("tenant1", "u1", null, null);

    assertThat(ctx.getResources()).contains("public.view", "package.resource");
    assertThat(ctx.getScopeType()).isEqualTo(AuthorizationScopeType.TENANT);
    assertThat(ctx.getActorRole()).isEqualTo(AuthorizationActorRole.TENANT_OWNER);
    assertThat(ctx.getVersion()).isEqualTo(7L);
    verify(tenantPackageRepo).findResourceCodesByTenantId("tenant1");
  }

  @Test
  void calculate_tenantMemberWithTenantAdminResource_usesTenantAdminActorRole() {
    User user = new User();
    user.setSuperAdmin(false);
    RoleGrantedAuthority role = new RoleGrantedAuthority("role-admin", "ROLE_CUSTOM");
    TenantRepository tenantRepository = mock(TenantRepository.class);
    Tenant tenant = new Tenant();
    tenant.setOwnerId("owner");
    tenant.setTenantType(TenantType.ORGANIZATION);

    when(usersService.findByIdForAuthorization("u1")).thenReturn(Optional.of(user));
    when(tenantRepositoryProvider.getIfAvailable()).thenReturn(tenantRepository);
    when(tenantRepository.findById("tenant1")).thenReturn(Optional.of(tenant));
    when(tenantRepository.hasUser("tenant1", "u1")).thenReturn(true);
    when(usersService.loadRolesByUserId("tenant1", "u1")).thenReturn(List.of(role));
    when(usersService.loadRolesByUserId(null, "u1")).thenReturn(List.of());
    when(usersService.loadResourcesInRoleIds(List.of("role-admin")))
        .thenReturn(Set.of(AuthorizationResourceNamespaces.TENANT_ADMIN));

    AuthorizationContext ctx = service.calculate("tenant1", "u1", null, null);

    assertThat(ctx.getResources()).contains(AuthorizationResourceNamespaces.TENANT_ADMIN);
    assertThat(ctx.getActorRole()).isEqualTo(AuthorizationActorRole.TENANT_ADMIN);
    assertThat(ctx.getAttribute("X-Actor-Role")).isEqualTo("TENANT_ADMIN");
  }

  @Test
  void calculate_withRoles_loadsResources() {
    User user = new User();
    user.setSuperAdmin(false);
    RoleGrantedAuthority role = new RoleGrantedAuthority("role1", "ROLE_USER");

    when(usersService.findByIdForAuthorization("u1")).thenReturn(Optional.of(user));
    when(usersService.loadRolesByUserId(null, "u1")).thenReturn(List.of(role));
    when(usersService.loadResourcesInRoleIds(List.of("role1"))).thenReturn(Set.of("users.view", "users.edit"));

    AuthorizationContext ctx = service.calculate(null, "u1", null, null);

    assertThat(ctx.getResources()).contains("users.view", "users.edit");
    assertThat(ctx.getRoles()).contains("ROLE_USER");
  }

  @Test
  void calculate_tenantContext_mergesTenantAndGlobalRoles() {
    User user = new User();
    user.setSuperAdmin(false);
    RoleGrantedAuthority tenantRole = new RoleGrantedAuthority("role-tenant", "ROLE_TENANT");
    RoleGrantedAuthority globalRole = new RoleGrantedAuthority("role-global", "ROLE_GLOBAL");
    TenantRepository tenantRepository = mock(TenantRepository.class);
    Tenant tenant = new Tenant();
    tenant.setOwnerId("owner");

    when(usersService.findByIdForAuthorization("u1")).thenReturn(Optional.of(user));
    when(tenantRepositoryProvider.getIfAvailable()).thenReturn(tenantRepository);
    when(tenantRepository.findById("tenant1")).thenReturn(Optional.of(tenant));
    when(tenantRepository.hasUser("tenant1", "u1")).thenReturn(true);
    when(usersService.loadRolesByUserId("tenant1", "u1")).thenReturn(List.of(tenantRole));
    when(usersService.loadRolesByUserId(null, "u1")).thenReturn(List.of(globalRole));
    when(usersService.loadResourcesInRoleIds(List.of("role-tenant", "role-global")))
        .thenReturn(Set.of("tenant.resource", "global.resource"));

    AuthorizationContext ctx = service.calculate("tenant1", "u1", null, null);

    assertThat(ctx.getRoles()).containsExactly("ROLE_TENANT", "ROLE_GLOBAL");
    assertThat(ctx.getResources()).contains("tenant.resource", "global.resource");
    verify(usersService).loadRolesByUserId("tenant1", "u1");
    verify(usersService).loadRolesByUserId(null, "u1");
  }

  @Test
  void calculate_selectedRole_usesOnlyThatRole() {
    User user = new User();
    user.setSuperAdmin(false);
    RoleGrantedAuthority role1 = new RoleGrantedAuthority("role1", "ROLE_USER");
    RoleGrantedAuthority role2 = new RoleGrantedAuthority("role2", "ROLE_MANAGER");

    when(usersService.findByIdForAuthorization("u1")).thenReturn(Optional.of(user));
    when(usersService.loadRolesByUserId(null, "u1")).thenReturn(List.of(role1, role2));
    when(usersService.loadResourcesInRoleIds(List.of("role2"))).thenReturn(Set.of("manager.resource"));

    AuthorizationContext ctx = service.calculate(null, "u1", "ctx1", Map.of("X-Role-Id", "role2"));

    assertThat(ctx.getRoles()).containsExactly("ROLE_MANAGER");
    assertThat(ctx.getResources()).containsExactly("manager.resource");
    assertThat(ctx.getAttribute("X-Role-Id")).isEqualTo("role2");
  }

  @Test
  void calculate_selectedRoleNotOwned_throwsAccessDeniedException() {
    User user = new User();
    user.setSuperAdmin(false);
    RoleGrantedAuthority role = new RoleGrantedAuthority("role1", "ROLE_USER");

    when(usersService.findByIdForAuthorization("u1")).thenReturn(Optional.of(user));
    when(usersService.loadRolesByUserId(null, "u1")).thenReturn(List.of(role));

    assertThatThrownBy(() -> service.calculate(null, "u1", "ctx1", Map.of("X-Role-Id", "role-missing")))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("当前用户未拥有指定角色");
  }

  @Test
  void calculate_publicResources_alwaysIncluded() {
    User user = new User();
    user.setSuperAdmin(false);
    ResourceService resourceService = mock(ResourceService.class);

    when(usersService.findByIdForAuthorization("u1")).thenReturn(Optional.of(user));
    when(usersService.loadRolesByUserId(null, "u1")).thenReturn(List.of());
    when(resourceServiceProvider.getIfAvailable()).thenReturn(resourceService);
    when(resourceService.findPublicAccessCodes()).thenReturn(Set.of("public.resource"));

    AuthorizationContext ctx = service.calculate(null, "u1", null, null);

    assertThat(ctx.getResources()).contains("public.resource");
  }

  @Test
  void calculate_mergesSelfAndCustomDataScopes() {
    User user = new User();
    user.setSuperAdmin(false);
    RoleGrantedAuthority role1 = new RoleGrantedAuthority("role1", "ROLE_USER");
    RoleGrantedAuthority role2 = new RoleGrantedAuthority("role2", "ROLE_MANAGER");
    RoleResourceGrant grant1 = new RoleResourceGrant();
    grant1.setDataScopeId("scope-self");
    RoleResourceGrant grant2 = new RoleResourceGrant();
    grant2.setDataScopeId("scope-custom");
    DataScope selfScope = new DataScope();
    selfScope.setId("scope-self");
    selfScope.setType(DataScopeType.SELF);
    DataScope customScope = new DataScope();
    customScope.setId("scope-custom");
    customScope.setType(DataScopeType.CUSTOM);
    customScope.setCustomDeptIds(Set.of("dept-a", "dept-b"));
    RoleResourceGrantRepository grantRepository = mock(RoleResourceGrantRepository.class);
    DataScopeRepository dataScopeRepo = mock(DataScopeRepository.class);

    when(usersService.findByIdForAuthorization("u1")).thenReturn(Optional.of(user));
    when(usersService.loadRolesByUserId(null, "u1")).thenReturn(List.of(role1, role2));
    when(usersService.loadResourcesInRoleIds(List.of("role1", "role2"))).thenReturn(Set.of());
    when(roleResourceGrantRepositoryProvider.getIfAvailable()).thenReturn(grantRepository);
    when(grantRepository.findByRoleIdIn(List.of("role1", "role2"))).thenReturn(List.of(grant1, grant2));
    when(dataScopeRepositoryProvider.getIfAvailable()).thenReturn(dataScopeRepo);
    when(dataScopeRepo.findAllById(anyCollection())).thenReturn(List.of(selfScope, customScope));

    AuthorizationContext ctx = service.calculate(null, "u1", null, null);

    assertThat(ctx.getDataScopeType()).isEqualTo(DataScopeType.CUSTOM.name());
    assertThat(ctx.getDeptIds()).containsExactlyInAnyOrder("dept-a", "dept-b");
    assertThat(ctx.getDataScopeIncludeSelf()).isTrue();
  }

  @Test
  void calculate_allDataScopeOverridesRestrictiveScopes() {
    User user = new User();
    user.setSuperAdmin(false);
    RoleGrantedAuthority role = new RoleGrantedAuthority("role1", "ROLE_ADMIN");
    RoleResourceGrant grant = new RoleResourceGrant();
    grant.setDataScopeId("scope-all");
    DataScope allScope = new DataScope();
    allScope.setId("scope-all");
    allScope.setType(DataScopeType.ALL);
    RoleResourceGrantRepository grantRepository = mock(RoleResourceGrantRepository.class);
    DataScopeRepository dataScopeRepo = mock(DataScopeRepository.class);

    when(usersService.findByIdForAuthorization("u1")).thenReturn(Optional.of(user));
    when(usersService.loadRolesByUserId(null, "u1")).thenReturn(List.of(role));
    when(usersService.loadResourcesInRoleIds(List.of("role1"))).thenReturn(Set.of());
    when(roleResourceGrantRepositoryProvider.getIfAvailable()).thenReturn(grantRepository);
    when(grantRepository.findByRoleIdIn(List.of("role1"))).thenReturn(List.of(grant));
    when(dataScopeRepositoryProvider.getIfAvailable()).thenReturn(dataScopeRepo);
    when(dataScopeRepo.findAllById(anyCollection())).thenReturn(List.of(allScope));

    AuthorizationContext ctx = service.calculate(null, "u1", null, null);

    assertThat(ctx.getDataScopeType()).isEqualTo(DataScopeType.ALL.name());
    assertThat(ctx.getDeptIds()).isEmpty();
    assertThat(ctx.getDataScopeIncludeSelf()).isFalse();
  }
}
