package org.simplepoint.plugin.rbac.core.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.anyCollection;
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
import org.simplepoint.core.AuthorizationPermissionNamespaces;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.authority.RoleGrantedAuthority;
import org.simplepoint.plugin.rbac.core.api.repository.DataScopeRepository;
import org.simplepoint.plugin.rbac.core.api.repository.FieldScopeRepository;
import org.simplepoint.plugin.rbac.core.api.repository.RolePermissionsRelevanceRepository;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.repository.FeaturePermissionRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.OrganizationRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.security.entity.DataScope;
import org.simplepoint.security.entity.DataScopeType;
import org.simplepoint.security.entity.RolePermissionsRelevance;
import org.simplepoint.security.entity.User;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;

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

  @Mock
  ObjectProvider<RolePermissionsRelevanceRepository> rolePermissionsRelevanceRepositoryProvider;

  @Mock
  ObjectProvider<DataScopeRepository> dataScopeRepositoryProvider;

  @Mock
  ObjectProvider<FieldScopeRepository> fieldScopeRepositoryProvider;

  @Mock
  ObjectProvider<OrganizationRepository> organizationRepositoryProvider;

  // Manual construction ensures the correct mock is passed to each constructor parameter
  // (Mockito @InjectMocks cannot distinguish multiple ObjectProvider<?> mocks without -parameters flag)
  AuthorizationContextServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new AuthorizationContextServiceImpl(
        usersService,
        featurePermissionRelevanceRepositoryProvider,
        tenantPackageRelevanceRepositoryProvider,
        tenantRepositoryProvider,
        rolePermissionsRelevanceRepositoryProvider,
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
  void calculate_defaultTenantString_treatedAsNull_skipsTenantCheck() {
    User user = new User();
    user.setSuperAdmin(false);
    when(usersService.findByIdForAuthorization("u1")).thenReturn(Optional.of(user));
    when(usersService.loadRolesByUserId(null, "u1")).thenReturn(List.of());
    when(featurePermissionRelevanceRepositoryProvider.getIfAvailable()).thenReturn(null);
    when(tenantPackageRelevanceRepositoryProvider.getIfAvailable()).thenReturn(null);

    // "default" is normalized to null — no tenant lookup performed
    AuthorizationContext ctx = service.calculate("default", "u1", "ctx1", null);

    assertThat(ctx).isNotNull();
    assertThat(ctx.getUserId()).isEqualTo("u1");
    assertThat(ctx.getContextId()).isEqualTo("ctx1");
    assertThat(ctx.getScopeType()).isEqualTo(AuthorizationScopeType.PERSONAL);
    assertThat(ctx.getActorRole()).isEqualTo(AuthorizationActorRole.PERSONAL_MEMBER);
    verify(tenantRepositoryProvider).getIfAvailable();
  }

  @Test
  void calculate_nullTenant_doesNotApplyTenantFiltering() {
    User user = new User();
    user.setSuperAdmin(false);

    when(usersService.findByIdForAuthorization("u1")).thenReturn(Optional.of(user));
    when(usersService.loadRolesByUserId(null, "u1")).thenReturn(List.of());
    when(featurePermissionRelevanceRepositoryProvider.getIfAvailable()).thenReturn(null);
    when(tenantPackageRelevanceRepositoryProvider.getIfAvailable()).thenReturn(null);

    AuthorizationContext ctx = service.calculate(null, "u1", "ctx1", null);

    assertThat(ctx.getAttribute("X-Tenant-Id")).isNull();
    assertThat(ctx.getAttribute("X-Context-Id")).isEqualTo("ctx1");
    assertThat(ctx.getAttribute("X-User-Id")).isEqualTo("u1");
    assertThat(ctx.getAttribute("X-Scope-Type")).isEqualTo("PERSONAL");
    assertThat(ctx.getAttribute("X-Actor-Role")).isEqualTo("PERSONAL_MEMBER");
    verify(tenantRepositoryProvider).getIfAvailable();
  }

  @Test
  void calculate_superAdmin_skipsTenantCheck() {
    User user = new User();
    user.setSuperAdmin(true);
    when(usersService.findByIdForAuthorization("u1")).thenReturn(Optional.of(user));
    when(usersService.loadRolesByUserId("tenant1", "u1")).thenReturn(List.of());
    when(featurePermissionRelevanceRepositoryProvider.getIfAvailable()).thenReturn(null);
    when(tenantPackageRelevanceRepositoryProvider.getIfAvailable()).thenReturn(null);

    AuthorizationContext ctx = service.calculate("tenant1", "u1", null, null);

    assertThat(ctx.getIsAdministrator()).isEqualTo(Boolean.TRUE);
    assertThat(ctx.getScopeType()).isEqualTo(AuthorizationScopeType.TENANT);
    assertThat(ctx.getActorRole()).isEqualTo(AuthorizationActorRole.TENANT_ADMIN);
    verify(tenantRepositoryProvider).getIfAvailable();
  }

  @Test
  void calculate_superAdminWithoutTenant_usesPlatformScope() {
    User user = new User();
    user.setSuperAdmin(true);
    when(usersService.findByIdForAuthorization("admin")).thenReturn(Optional.of(user));
    when(usersService.loadRolesByUserId(null, "admin")).thenReturn(List.of());
    when(featurePermissionRelevanceRepositoryProvider.getIfAvailable()).thenReturn(null);
    when(tenantPackageRelevanceRepositoryProvider.getIfAvailable()).thenReturn(null);

    AuthorizationContext ctx = service.calculate(null, "admin", "ctx-admin", null);

    assertThat(ctx.getScopeType()).isEqualTo(AuthorizationScopeType.PLATFORM);
    assertThat(ctx.getActorRole()).isEqualTo(AuthorizationActorRole.PLATFORM_ADMIN);
    assertThat(ctx.getAttribute("X-Scope-Type")).isEqualTo("PLATFORM");
    assertThat(ctx.getAttribute("X-Actor-Role")).isEqualTo("PLATFORM_ADMIN");
    assertThat(ctx.getAttribute("X-Tenant-Id")).isNull();
  }

  @Test
  void calculate_userWithoutTenant_usesPersonalTenantWhenAvailable() {
    User user = new User();
    user.setSuperAdmin(false);
    final TenantRepository tenantRepository = mock(TenantRepository.class);
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
    when(featurePermissionRelevanceRepositoryProvider.getIfAvailable()).thenReturn(null);
    when(tenantPackageRelevanceRepositoryProvider.getIfAvailable()).thenReturn(null);

    AuthorizationContext ctx = service.calculate(null, "u1", "ctx1", null);

    assertThat(ctx.getScopeType()).isEqualTo(AuthorizationScopeType.PERSONAL);
    assertThat(ctx.getActorRole()).isEqualTo(AuthorizationActorRole.PERSONAL_OWNER);
    assertThat(ctx.getAttribute("X-Tenant-Id")).isEqualTo("personal-1");
    assertThat(ctx.getAttribute("X-Scope-Type")).isEqualTo("PERSONAL");
    assertThat(ctx.getAttribute("X-Actor-Role")).isEqualTo("PERSONAL_OWNER");
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
  void calculate_tenantUserWhenTenantRepositoryUnavailable_throwsAccessDeniedException() {
    User user = new User();
    user.setSuperAdmin(false);

    when(usersService.findByIdForAuthorization("u1")).thenReturn(Optional.of(user));
    when(tenantRepositoryProvider.getIfAvailable()).thenReturn(null);

    assertThatThrownBy(() -> service.calculate("tenant1", "u1", null, null))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("无法验证指定租户");
  }

  @Test
  void calculate_tenantOwner_addsPackageAndFeaturePermissions() {
    User user = new User();
    user.setSuperAdmin(false);
    final TenantRepository tenantRepository = mock(TenantRepository.class);
    Tenant tenant = new Tenant();
    tenant.setOwnerId("u1"); // user is owner
    tenant.setTenantType(TenantType.ORGANIZATION);

    TenantPackageRelevanceRepository tenantPackageRepo = mock(TenantPackageRelevanceRepository.class);
    FeaturePermissionRelevanceRepository featurePermRepo = mock(FeaturePermissionRelevanceRepository.class);

    when(usersService.findByIdForAuthorization("u1")).thenReturn(Optional.of(user));
    when(tenantRepositoryProvider.getIfAvailable()).thenReturn(tenantRepository);
    when(tenantRepository.findById("tenant1")).thenReturn(Optional.of(tenant));
    when(tenantRepository.hasUser("tenant1", "u1")).thenReturn(true);
    when(tenantRepository.getTenantPermissionVersion("tenant1")).thenReturn(7L);
    when(usersService.loadRolesByUserId("tenant1", "u1")).thenReturn(List.of());
    when(tenantPackageRelevanceRepositoryProvider.getIfAvailable()).thenReturn(tenantPackageRepo);
    when(featurePermissionRelevanceRepositoryProvider.getIfAvailable()).thenReturn(featurePermRepo);
    when(tenantPackageRepo.findFeatureCodesByTenantId("tenant1")).thenReturn(Set.of("feature1"));
    when(featurePermRepo.findPermissionAuthoritiesByTenantId("tenant1")).thenReturn(Set.of("perm.tenant1"));
    when(featurePermRepo.findPublicAccessFeatureCodes()).thenReturn(Set.of());
    when(featurePermRepo.findPermissionAuthoritiesByPublicAccessFeatures()).thenReturn(Set.of());

    AuthorizationContext ctx = service.calculate("tenant1", "u1", null, null);

    assertThat(ctx.getPermissions()).contains("feature1", "perm.tenant1");
    assertThat(ctx.getScopeType()).isEqualTo(AuthorizationScopeType.TENANT);
    assertThat(ctx.getActorRole()).isEqualTo(AuthorizationActorRole.TENANT_OWNER);
    assertThat(ctx.getVersion()).isEqualTo(7L);
    assertThat(ctx.getAttribute("X-Scope-Type")).isEqualTo("TENANT");
    assertThat(ctx.getAttribute("X-Actor-Role")).isEqualTo("TENANT_OWNER");
    verify(tenantPackageRepo).findFeatureCodesByTenantId("tenant1");
    verify(featurePermRepo).findPermissionAuthoritiesByTenantId("tenant1");
  }

  @Test
  void calculate_tenantMemberWithTenantAdminPermission_usesTenantAdminActorRole() {
    User user = new User();
    user.setSuperAdmin(false);
    final RoleGrantedAuthority role = new RoleGrantedAuthority("role-admin", "ROLE_CUSTOM");
    final TenantRepository tenantRepository = mock(TenantRepository.class);
    Tenant tenant = new Tenant();
    tenant.setOwnerId("owner");
    tenant.setTenantType(TenantType.ORGANIZATION);

    when(usersService.findByIdForAuthorization("u1")).thenReturn(Optional.of(user));
    when(tenantRepositoryProvider.getIfAvailable()).thenReturn(tenantRepository);
    when(tenantRepository.findById("tenant1")).thenReturn(Optional.of(tenant));
    when(tenantRepository.hasUser("tenant1", "u1")).thenReturn(true);
    when(usersService.loadRolesByUserId("tenant1", "u1")).thenReturn(List.of(role));
    when(usersService.loadRolesByUserId(null, "u1")).thenReturn(List.of());
    when(usersService.loadPermissionsInRoleIds(List.of("role-admin")))
        .thenReturn(Set.of(AuthorizationPermissionNamespaces.TENANT_ADMIN));
    when(featurePermissionRelevanceRepositoryProvider.getIfAvailable()).thenReturn(null);
    when(tenantPackageRelevanceRepositoryProvider.getIfAvailable()).thenReturn(null);

    AuthorizationContext ctx = service.calculate("tenant1", "u1", null, null);

    assertThat(ctx.getScopeType()).isEqualTo(AuthorizationScopeType.TENANT);
    assertThat(ctx.getActorRole()).isEqualTo(AuthorizationActorRole.TENANT_ADMIN);
    assertThat(ctx.getAttribute("X-Actor-Role")).isEqualTo("TENANT_ADMIN");
  }

  @Test
  void calculate_withRoles_loadsPermissions() {
    User user = new User();
    user.setSuperAdmin(false);
    RoleGrantedAuthority role = new RoleGrantedAuthority("role1", "ROLE_USER");
    FeaturePermissionRelevanceRepository featurePermRepo = mock(FeaturePermissionRelevanceRepository.class);

    when(usersService.findByIdForAuthorization("u1")).thenReturn(Optional.of(user));
    when(usersService.loadRolesByUserId(null, "u1")).thenReturn(List.of(role));
    when(usersService.loadPermissionsInRoleIds(List.of("role1"))).thenReturn(Set.of("perm1", "perm2"));
    when(featurePermissionRelevanceRepositoryProvider.getIfAvailable()).thenReturn(featurePermRepo);
    when(featurePermRepo.findFeatureCodesByPermissionAuthorities(anyCollection())).thenReturn(Set.of());
    when(featurePermRepo.findPublicAccessFeatureCodes()).thenReturn(Set.of());
    when(featurePermRepo.findPermissionAuthoritiesByPublicAccessFeatures()).thenReturn(Set.of());
    when(tenantPackageRelevanceRepositoryProvider.getIfAvailable()).thenReturn(null);

    AuthorizationContext ctx = service.calculate(null, "u1", null, null);

    assertThat(ctx.getPermissions()).contains("perm1", "perm2");
    assertThat(ctx.getRoles()).contains("ROLE_USER");
  }

  @Test
  void calculate_tenantContext_mergesTenantAndGlobalRoles() {
    User user = new User();
    user.setSuperAdmin(false);
    final RoleGrantedAuthority tenantRole = new RoleGrantedAuthority("role-tenant", "ROLE_TENANT");
    final RoleGrantedAuthority globalRole = new RoleGrantedAuthority("role-global", "ROLE_GLOBAL");
    final FeaturePermissionRelevanceRepository featurePermRepo = mock(FeaturePermissionRelevanceRepository.class);
    TenantRepository tenantRepository = mock(TenantRepository.class);
    Tenant tenant = new Tenant();
    tenant.setOwnerId("owner");

    when(usersService.findByIdForAuthorization("u1")).thenReturn(Optional.of(user));
    when(tenantRepositoryProvider.getIfAvailable()).thenReturn(tenantRepository);
    when(tenantRepository.findById("tenant1")).thenReturn(Optional.of(tenant));
    when(tenantRepository.hasUser("tenant1", "u1")).thenReturn(true);
    when(usersService.loadRolesByUserId("tenant1", "u1")).thenReturn(List.of(tenantRole));
    when(usersService.loadRolesByUserId(null, "u1")).thenReturn(List.of(globalRole));
    when(usersService.loadPermissionsInRoleIds(List.of("role-tenant", "role-global")))
        .thenReturn(Set.of("perm.tenant", "perm.global"));
    when(featurePermissionRelevanceRepositoryProvider.getIfAvailable()).thenReturn(featurePermRepo);
    when(featurePermRepo.findFeatureCodesByPermissionAuthorities(anyCollection())).thenReturn(Set.of());
    when(featurePermRepo.findPublicAccessFeatureCodes()).thenReturn(Set.of());
    when(featurePermRepo.findPermissionAuthoritiesByPublicAccessFeatures()).thenReturn(Set.of());
    when(tenantPackageRelevanceRepositoryProvider.getIfAvailable()).thenReturn(null);

    AuthorizationContext ctx = service.calculate("tenant1", "u1", null, null);

    assertThat(ctx.getRoles()).containsExactly("ROLE_TENANT", "ROLE_GLOBAL");
    assertThat(ctx.getPermissions()).contains("perm.tenant", "perm.global");
    verify(usersService).loadRolesByUserId("tenant1", "u1");
    verify(usersService).loadRolesByUserId(null, "u1");
  }

  @Test
  void calculate_selectedRole_usesOnlyThatRole() {
    User user = new User();
    user.setSuperAdmin(false);
    final RoleGrantedAuthority role1 = new RoleGrantedAuthority("role1", "ROLE_USER");
    final RoleGrantedAuthority role2 = new RoleGrantedAuthority("role2", "ROLE_MANAGER");
    final FeaturePermissionRelevanceRepository featurePermRepo = mock(FeaturePermissionRelevanceRepository.class);

    when(usersService.findByIdForAuthorization("u1")).thenReturn(Optional.of(user));
    when(usersService.loadRolesByUserId(null, "u1")).thenReturn(List.of(role1, role2));
    when(usersService.loadPermissionsInRoleIds(List.of("role2"))).thenReturn(Set.of("perm.manager"));
    when(featurePermissionRelevanceRepositoryProvider.getIfAvailable()).thenReturn(featurePermRepo);
    when(featurePermRepo.findFeatureCodesByPermissionAuthorities(Set.of("perm.manager"))).thenReturn(Set.of());
    when(featurePermRepo.findPublicAccessFeatureCodes()).thenReturn(Set.of());
    when(featurePermRepo.findPermissionAuthoritiesByPublicAccessFeatures()).thenReturn(Set.of());
    when(tenantPackageRelevanceRepositoryProvider.getIfAvailable()).thenReturn(null);

    AuthorizationContext ctx = service.calculate(null, "u1", "ctx1", Map.of("X-Role-Id", "role2"));

    assertThat(ctx.getRoles()).containsExactly("ROLE_MANAGER");
    assertThat(ctx.getPermissions()).containsExactly("perm.manager");
    assertThat(ctx.getAttribute("X-Role-Id")).isEqualTo("role2");
  }

  @Test
  void calculate_selectedRoleNotOwned_throwsAccessDeniedException() {
    User user = new User();
    user.setSuperAdmin(false);
    final RoleGrantedAuthority role = new RoleGrantedAuthority("role1", "ROLE_USER");

    when(usersService.findByIdForAuthorization("u1")).thenReturn(Optional.of(user));
    when(usersService.loadRolesByUserId(null, "u1")).thenReturn(List.of(role));

    assertThatThrownBy(() -> service.calculate(null, "u1", "ctx1", Map.of("X-Role-Id", "role-missing")))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("当前用户未拥有指定角色");
  }

  @Test
  void calculate_publicAccessFeatures_alwaysIncludedRegardlessOfRoles() {
    User user = new User();
    user.setSuperAdmin(false);
    FeaturePermissionRelevanceRepository featurePermRepo = mock(FeaturePermissionRelevanceRepository.class);

    when(usersService.findByIdForAuthorization("u1")).thenReturn(Optional.of(user));
    when(usersService.loadRolesByUserId(null, "u1")).thenReturn(List.of());
    when(featurePermissionRelevanceRepositoryProvider.getIfAvailable()).thenReturn(featurePermRepo);
    when(featurePermRepo.findPublicAccessFeatureCodes()).thenReturn(Set.of("public-feature"));
    when(featurePermRepo.findPermissionAuthoritiesByPublicAccessFeatures()).thenReturn(Set.of("public.perm1"));
    when(tenantPackageRelevanceRepositoryProvider.getIfAvailable()).thenReturn(null);

    AuthorizationContext ctx = service.calculate(null, "u1", null, null);

    assertThat(ctx.getPermissions()).contains("public-feature", "public.perm1");
  }

  @Test
  void calculate_withRolePermissions_resolvesFeatureCodesAndPublicFeaturesForMenuRouting() {
    User user = new User();
    user.setSuperAdmin(false);
    RoleGrantedAuthority role = new RoleGrantedAuthority("role1", "ROLE_USER");
    FeaturePermissionRelevanceRepository featurePermRepo = mock(FeaturePermissionRelevanceRepository.class);

    when(usersService.findByIdForAuthorization("u1")).thenReturn(Optional.of(user));
    when(usersService.loadRolesByUserId("tenant1", "u1")).thenReturn(List.of(role));
    when(usersService.loadRolesByUserId(null, "u1")).thenReturn(List.of());
    when(usersService.loadPermissionsInRoleIds(List.of("role1"))).thenReturn(Set.of("perm.users.view"));
    when(featurePermissionRelevanceRepositoryProvider.getIfAvailable()).thenReturn(featurePermRepo);
    when(featurePermRepo.findFeatureCodesByPermissionAuthorities(Set.of("perm.users.view")))
        .thenReturn(Set.of("feature.users"));
    when(featurePermRepo.findPublicAccessFeatureCodes()).thenReturn(Set.of("feature.public"));
    when(featurePermRepo.findPermissionAuthoritiesByPublicAccessFeatures()).thenReturn(Set.of("perm.public"));
    when(tenantPackageRelevanceRepositoryProvider.getIfAvailable()).thenReturn(null);
    TenantRepository tenantRepository = mock(TenantRepository.class);
    Tenant tenant = new Tenant();
    tenant.setOwnerId("owner1");
    when(tenantRepositoryProvider.getIfAvailable()).thenReturn(tenantRepository);
    when(tenantRepository.findById("tenant1")).thenReturn(Optional.of(tenant));
    when(tenantRepository.hasUser("tenant1", "u1")).thenReturn(true);

    AuthorizationContext ctx = service.calculate("tenant1", "u1", "ctx1", null);

    assertThat(ctx.getPermissions()).contains(
        "perm.users.view",
        "feature.users",
        "feature.public",
        "perm.public"
    );
    assertThat(ctx.getAttribute("X-Tenant-Id")).isEqualTo("tenant1");
    assertThat(ctx.getAttribute("X-Context-Id")).isEqualTo("ctx1");
  }

  @Test
  void calculate_mergesSelfAndCustomDataScopes() {
    User user = new User();
    user.setSuperAdmin(false);
    final RoleGrantedAuthority role1 = new RoleGrantedAuthority("role1", "ROLE_USER");
    final RoleGrantedAuthority role2 = new RoleGrantedAuthority("role2", "ROLE_MANAGER");
    RolePermissionsRelevance relevance1 = new RolePermissionsRelevance();
    relevance1.setDataScopeId("scope-self");
    RolePermissionsRelevance relevance2 = new RolePermissionsRelevance();
    relevance2.setDataScopeId("scope-custom");
    DataScope selfScope = new DataScope();
    selfScope.setId("scope-self");
    selfScope.setType(DataScopeType.SELF);
    DataScope customScope = new DataScope();
    customScope.setId("scope-custom");
    customScope.setType(DataScopeType.CUSTOM);
    customScope.setCustomDeptIds(Set.of("dept-a", "dept-b"));
    RolePermissionsRelevanceRepository rolePermissionsRepo = mock(RolePermissionsRelevanceRepository.class);
    DataScopeRepository dataScopeRepo = mock(DataScopeRepository.class);

    when(usersService.findByIdForAuthorization("u1")).thenReturn(Optional.of(user));
    when(usersService.loadRolesByUserId(null, "u1")).thenReturn(List.of(role1, role2));
    when(usersService.loadPermissionsInRoleIds(List.of("role1", "role2"))).thenReturn(Set.of());
    when(featurePermissionRelevanceRepositoryProvider.getIfAvailable()).thenReturn(null);
    when(tenantPackageRelevanceRepositoryProvider.getIfAvailable()).thenReturn(null);
    when(rolePermissionsRelevanceRepositoryProvider.getIfAvailable()).thenReturn(rolePermissionsRepo);
    when(rolePermissionsRepo.findByRoleIdIn(List.of("role1", "role2"))).thenReturn(List.of(relevance1, relevance2));
    when(dataScopeRepositoryProvider.getIfAvailable()).thenReturn(dataScopeRepo);
    when(dataScopeRepo.findAllById(Set.of("scope-self", "scope-custom"))).thenReturn(List.of(selfScope, customScope));
    when(fieldScopeRepositoryProvider.getIfAvailable()).thenReturn(null);

    AuthorizationContext ctx = service.calculate(null, "u1", null, null);

    assertThat(ctx.getDataScopeType()).isEqualTo(DataScopeType.CUSTOM.name());
    assertThat(ctx.getDeptIds()).containsExactlyInAnyOrder("dept-a", "dept-b");
    assertThat(ctx.getDataScopeIncludeSelf()).isTrue();
  }

  @Test
  void calculate_allDataScopeOverridesRestrictiveScopes() {
    User user = new User();
    user.setSuperAdmin(false);
    final RoleGrantedAuthority role = new RoleGrantedAuthority("role1", "ROLE_ADMIN");
    RolePermissionsRelevance relevance = new RolePermissionsRelevance();
    relevance.setDataScopeId("scope-all");
    DataScope allScope = new DataScope();
    allScope.setId("scope-all");
    allScope.setType(DataScopeType.ALL);
    RolePermissionsRelevanceRepository rolePermissionsRepo = mock(RolePermissionsRelevanceRepository.class);
    DataScopeRepository dataScopeRepo = mock(DataScopeRepository.class);

    when(usersService.findByIdForAuthorization("u1")).thenReturn(Optional.of(user));
    when(usersService.loadRolesByUserId(null, "u1")).thenReturn(List.of(role));
    when(usersService.loadPermissionsInRoleIds(List.of("role1"))).thenReturn(Set.of());
    when(featurePermissionRelevanceRepositoryProvider.getIfAvailable()).thenReturn(null);
    when(tenantPackageRelevanceRepositoryProvider.getIfAvailable()).thenReturn(null);
    when(rolePermissionsRelevanceRepositoryProvider.getIfAvailable()).thenReturn(rolePermissionsRepo);
    when(rolePermissionsRepo.findByRoleIdIn(List.of("role1"))).thenReturn(List.of(relevance));
    when(dataScopeRepositoryProvider.getIfAvailable()).thenReturn(dataScopeRepo);
    when(dataScopeRepo.findAllById(Set.of("scope-all"))).thenReturn(List.of(allScope));
    when(fieldScopeRepositoryProvider.getIfAvailable()).thenReturn(null);

    AuthorizationContext ctx = service.calculate(null, "u1", null, null);

    assertThat(ctx.getDataScopeType()).isEqualTo(DataScopeType.ALL.name());
    assertThat(ctx.getDeptIds()).isEmpty();
    assertThat(ctx.getDataScopeIncludeSelf()).isFalse();
  }

  @Test
  void calculate_emptyCustomScopeStaysCustomForFailClosedFiltering() {
    User user = new User();
    user.setSuperAdmin(false);
    final RoleGrantedAuthority role = new RoleGrantedAuthority("role1", "ROLE_USER");
    RolePermissionsRelevance relevance = new RolePermissionsRelevance();
    relevance.setDataScopeId("scope-custom");
    DataScope customScope = new DataScope();
    customScope.setId("scope-custom");
    customScope.setType(DataScopeType.CUSTOM);
    customScope.setCustomDeptIds(Set.of());
    RolePermissionsRelevanceRepository rolePermissionsRepo = mock(RolePermissionsRelevanceRepository.class);
    DataScopeRepository dataScopeRepo = mock(DataScopeRepository.class);

    when(usersService.findByIdForAuthorization("u1")).thenReturn(Optional.of(user));
    when(usersService.loadRolesByUserId(null, "u1")).thenReturn(List.of(role));
    when(usersService.loadPermissionsInRoleIds(List.of("role1"))).thenReturn(Set.of());
    when(featurePermissionRelevanceRepositoryProvider.getIfAvailable()).thenReturn(null);
    when(tenantPackageRelevanceRepositoryProvider.getIfAvailable()).thenReturn(null);
    when(rolePermissionsRelevanceRepositoryProvider.getIfAvailable()).thenReturn(rolePermissionsRepo);
    when(rolePermissionsRepo.findByRoleIdIn(List.of("role1"))).thenReturn(List.of(relevance));
    when(dataScopeRepositoryProvider.getIfAvailable()).thenReturn(dataScopeRepo);
    when(dataScopeRepo.findAllById(Set.of("scope-custom"))).thenReturn(List.of(customScope));
    when(fieldScopeRepositoryProvider.getIfAvailable()).thenReturn(null);

    AuthorizationContext ctx = service.calculate(null, "u1", null, null);

    assertThat(ctx.getDataScopeType()).isEqualTo(DataScopeType.CUSTOM.name());
    assertThat(ctx.getDeptIds()).isEmpty();
    assertThat(ctx.getDataScopeIncludeSelf()).isFalse();
  }
}
