package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.simplepoint.plugin.rbac.tenant.service.support.BaseServiceSchemaTestSupport.stubBaseServiceSchema;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.authority.RoleGrantedAuthority;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantUserRelevance;
import org.simplepoint.plugin.rbac.tenant.api.pojo.command.TenantProfileUpdateCommand;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.TenantPackagesRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.TenantUsersRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantUserRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.plugin.rbac.tenant.api.vo.NamedTenantVo;
import org.simplepoint.plugin.rbac.tenant.api.vo.TenantContextType;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@ExtendWith(MockitoExtension.class)
class TenantServiceImplTest {

  @Mock
  TenantRepository repository;

  @Mock
  DetailsProviderService detailsProviderService;

  @Mock
  TenantPackageRelevanceRepository tenantPackageRelevanceRepository;

  @Mock
  TenantUserRelevanceRepository tenantUserRelevanceRepository;

  @Mock
  ResourceAuthorizationVersionService resourceAuthorizationVersionService;

  @Mock
  UsersService usersService;

  @InjectMocks
  TenantServiceImpl service;

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
    org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
  }

  // ── getTenantsByUserId ────────────────────────────────────────────────────

  @Test
  void getTenantsByUserId_delegatesToRepository() {
    NamedTenantVo vo = new NamedTenantVo("t1", "Tenant One", (TenantContextType) null);
    when(repository.getTenantsByUserId("user1")).thenReturn(Set.of(vo));

    Set<NamedTenantVo> result = service.getTenantsByUserId("user1");

    assertThat(result).containsExactly(vo);
    verify(repository).getTenantsByUserId("user1");
  }

  // ── getCurrentUserTenants ─────────────────────────────────────────────────

  @Test
  void getCurrentUserTenants_throwsWhenNotAuthenticated() {
    SecurityContextHolder.clearContext();
    assertThatThrownBy(() -> service.getCurrentUserTenants())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("当前未认证用户");
  }

  @Test
  void getCurrentUserTenants_delegatesToRepositoryForAuthenticatedUser() {
    setAuthentication("user1");
    Tenant personalTenant = new Tenant();
    personalTenant.setId("t1");
    personalTenant.setName("Tenant One");
    personalTenant.setTenantType(TenantType.PERSONAL);
    NamedTenantVo vo = new NamedTenantVo("t1", "Tenant One", TenantType.PERSONAL);
    when(repository.findPersonalTenantByOwnerId("user1")).thenReturn(Optional.of(personalTenant));
    when(tenantUserRelevanceRepository.authorized("t1")).thenReturn(List.of("user1"));
    when(repository.getTenantsByUserId("user1")).thenReturn(Set.of(vo));

    Set<NamedTenantVo> result = service.getCurrentUserTenants();

    assertThat(result).containsExactly(vo);
  }

  @Test
  void getCurrentUserTenants_missingPersonalTenant_createsIt() {
    setAuthentication("user1");
    Tenant savedTenant = new Tenant();
    savedTenant.setId("t-personal");
    savedTenant.setName("user1 的个人空间");
    savedTenant.setTenantType(TenantType.PERSONAL);
    when(repository.findPersonalTenantByOwnerId("user1")).thenReturn(Optional.empty());
    when(repository.save(any(Tenant.class))).thenReturn(savedTenant);
    when(repository.getTenantsByUserId("user1")).thenReturn(Set.of());
    when(tenantUserRelevanceRepository.authorized("t-personal")).thenReturn(List.of());

    Set<NamedTenantVo> result = service.getCurrentUserTenants();

    assertThat(result).containsExactly(new NamedTenantVo("t-personal", "user1 的个人空间", TenantType.PERSONAL));
    verify(repository).save(any(Tenant.class));
    verify(tenantUserRelevanceRepository).saveAll(any(Collection.class));
  }

  @Test
  void getCurrentUserTenants_addsSyntheticPlatformContextForAdministrator() {
    setAdminRole();
    Tenant personalTenant = new Tenant();
    personalTenant.setId("personal-admin");
    personalTenant.setName("admin 的个人空间");
    personalTenant.setTenantType(TenantType.PERSONAL);
    when(repository.findPersonalTenantByOwnerId("admin")).thenReturn(Optional.of(personalTenant));
    when(tenantUserRelevanceRepository.authorized("personal-admin")).thenReturn(List.of("admin"));
    when(repository.getTenantsByUserId("admin")).thenReturn(Set.of());

    Set<NamedTenantVo> result = service.getCurrentUserTenants();

    assertThat(result).containsExactly(
        new NamedTenantVo("__platform__", "平台工作台", TenantContextType.PLATFORM),
        new NamedTenantVo("personal-admin", "admin 的个人空间", TenantType.PERSONAL)
    );
  }

  // ── calculateAuthorizationContextId ──────────────────────────────────────────

  @Test
  void getCurrentUserRoles_returnsTenantAndGlobalRoles() {
    setAuthentication("user1");
    RoleGrantedAuthority tenantRole = new RoleGrantedAuthority(
        "role-tenant", "租户角色", "ROLE_TENANT"
    );
    RoleGrantedAuthority globalRole = new RoleGrantedAuthority(
        "role-global", "全局角色", "ROLE_GLOBAL"
    );
    when(repository.hasUser("tenant1", "user1")).thenReturn(true);
    when(usersService.loadRolesByUserId("tenant1", "user1")).thenReturn(List.of(tenantRole));
    when(usersService.loadRolesByUserId(null, "user1")).thenReturn(List.of(globalRole));

    Collection<RoleGrantedAuthority> result = service.getCurrentUserRoles("tenant1");

    assertThat(result).extracting(RoleGrantedAuthority::getId).containsExactly("role-tenant", "role-global");
    assertThat(result).extracting(RoleGrantedAuthority::getName).containsExactly("租户角色", "全局角色");
  }

  @Test
  void calculateAuthorizationContextId_throwsWhenNotAuthenticated() {
    SecurityContextHolder.clearContext();
    assertThatThrownBy(() -> service.calculateAuthorizationContextId("t1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("当前未认证用户");
  }

  @Test
  void calculateAuthorizationContextId_returnsHashForDefaultTenant() {
    setAuthentication("user1");
    Tenant personalTenant = new Tenant();
    personalTenant.setId("personal-1");
    when(repository.findPersonalTenantByOwnerId("user1")).thenReturn(Optional.of(personalTenant));
    when(tenantUserRelevanceRepository.authorized("personal-1")).thenReturn(List.of("user1"));
    when(repository.getTenantAuthorizationVersion("personal-1")).thenReturn(3L);

    String contextId = service.calculateAuthorizationContextId("default");

    assertThat(contextId).isNotBlank().hasSize(64); // SHA-256 hex is 64 chars
    verify(repository).findPersonalTenantByOwnerId("user1");
    verify(repository).getTenantAuthorizationVersion("personal-1");
  }

  @Test
  void calculateAuthorizationContextId_withNonDefaultTenantAndVersion() {
    setAuthentication("user1");
    when(repository.hasUser("tenant1", "user1")).thenReturn(true);
    when(repository.getTenantAuthorizationVersion("tenant1")).thenReturn(5L);

    String contextId = service.calculateAuthorizationContextId("tenant1");

    assertThat(contextId).isNotBlank().hasSize(64);
    verify(repository).hasUser("tenant1", "user1");
    verify(repository).getTenantAuthorizationVersion("tenant1");
  }

  @Test
  void calculateAuthorizationContextId_includesSelectedRoleInHash() {
    setAuthentication("user1");
    RoleGrantedAuthority role = new RoleGrantedAuthority("role-admin", "ROLE_ADMIN");
    when(repository.hasUser("tenant1", "user1")).thenReturn(true);
    when(usersService.loadRolesByUserId("tenant1", "user1")).thenReturn(List.of(role));
    when(usersService.loadRolesByUserId(null, "user1")).thenReturn(List.of());
    when(repository.getTenantAuthorizationVersion("tenant1")).thenReturn(5L);

    String allRolesContextId = service.calculateAuthorizationContextId("tenant1");
    String selectedRoleContextId = service.calculateAuthorizationContextId("tenant1", "role-admin");

    assertThat(selectedRoleContextId).isNotEqualTo(allRolesContextId);
  }

  @Test
  void calculateAuthorizationContextId_throwsWhenSelectedRoleNotOwned() {
    setAuthentication("user1");
    when(repository.hasUser("tenant1", "user1")).thenReturn(true);
    when(usersService.loadRolesByUserId("tenant1", "user1")).thenReturn(List.of());
    when(usersService.loadRolesByUserId(null, "user1")).thenReturn(List.of());

    assertThatThrownBy(() -> service.calculateAuthorizationContextId("tenant1", "role-missing"))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("当前用户未拥有指定角色");
  }

  @Test
  void calculateAuthorizationContextId_withNullAuthorizationVersion() {
    setAuthentication("user1");
    when(repository.hasUser("tenant1", "user1")).thenReturn(true);
    when(repository.getTenantAuthorizationVersion("tenant1")).thenReturn(null);

    String contextId = service.calculateAuthorizationContextId("tenant1");

    assertThat(contextId).isNotBlank().hasSize(64);
  }

  @Test
  void calculateAuthorizationContextId_throwsWhenUserNotInTenant() {
    setAuthentication("user1");
    when(repository.hasUser("tenant1", "user1")).thenReturn(false);

    assertThatThrownBy(() -> service.calculateAuthorizationContextId("tenant1"))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("当前用户未加入指定租户");
  }

  @Test
  void calculateAuthorizationContextId_usesDefaultContextWhenBlankTenantId() {
    setAuthentication("user1");
    Tenant personalTenant = new Tenant();
    personalTenant.setId("personal-1");
    when(repository.findPersonalTenantByOwnerId("user1")).thenReturn(Optional.of(personalTenant));
    when(repository.getTenantAuthorizationVersion("personal-1")).thenReturn(2L);

    String contextId = service.calculateAuthorizationContextId(null);

    assertThat(contextId).isNotBlank().hasSize(64);
    verify(repository).findPersonalTenantByOwnerId("user1");
    verify(repository).getTenantAuthorizationVersion("personal-1");
  }

  @Test
  void calculateAuthorizationContextId_usesPersonalTenantWhenTenantIdBlankString() {
    setAuthentication("user1");
    Tenant personalTenant = new Tenant();
    personalTenant.setId("personal-1");
    when(repository.findPersonalTenantByOwnerId("user1")).thenReturn(Optional.of(personalTenant));
    when(repository.getTenantAuthorizationVersion("personal-1")).thenReturn(4L);

    String contextId = service.calculateAuthorizationContextId("   ");

    assertThat(contextId).isNotBlank().hasSize(64);
    verify(repository).findPersonalTenantByOwnerId("user1");
    verify(repository).getTenantAuthorizationVersion("personal-1");
  }

  // ── authorizedPackages ────────────────────────────────────────────────────

  @Test
  void authorizedPackages_throwsWhenTenantIdIsNull() {
    assertThatThrownBy(() -> service.authorizedPackages(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("租户ID不能为空");
  }

  @Test
  void authorizedPackages_throwsWhenTenantIdIsBlank() {
    assertThatThrownBy(() -> service.authorizedPackages("  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("租户ID不能为空");
  }

  @Test
  void authorizedPackages_delegatesToRepository() {
    setAdminRole();
    Tenant tenant = new Tenant();
    tenant.setId("tenant1");
    tenant.setOwnerId("admin");
    when(repository.findById("tenant1")).thenReturn(Optional.of(tenant));
    List<String> expected = List.of("pkg.standard");
    when(tenantPackageRelevanceRepository.authorized("tenant1")).thenReturn(expected);

    Collection<String> result = service.authorizedPackages("tenant1");

    assertThat(result).isEqualTo(expected);
    verify(tenantPackageRelevanceRepository).authorized("tenant1");
  }

  // ── ownerItems ────────────────────────────────────────────────────────────

  @Test
  void ownerItems_delegatesToRepository() {
    Pageable pageable = Pageable.ofSize(10);
    when(tenantUserRelevanceRepository.items(pageable)).thenReturn(new PageImpl<>(List.of()));

    var result = service.ownerItems(pageable);

    assertThat(result).isNotNull();
    verify(tenantUserRelevanceRepository).items(pageable);
  }

  @Test
  void ownerItems_withKeywordUsesPagedRepositorySearch() {
    Pageable pageable = Pageable.ofSize(20);
    when(tenantUserRelevanceRepository.searchItems("alice", pageable))
        .thenReturn(new PageImpl<>(List.of()));

    var result = service.ownerItems("  alice  ", pageable);

    assertThat(result).isNotNull();
    verify(tenantUserRelevanceRepository).searchItems("alice", pageable);
    verify(tenantUserRelevanceRepository, never()).items(pageable);
  }

  @Test
  void userItems_validatesTenantButReturnsGlobalUserCandidates() {
    setAdminRole();
    Tenant tenant = new Tenant();
    tenant.setId("tenant1");
    tenant.setOwnerId("owner1");
    Pageable pageable = Pageable.ofSize(10);
    when(repository.findById("tenant1")).thenReturn(Optional.of(tenant));
    when(tenantUserRelevanceRepository.items(pageable)).thenReturn(new PageImpl<>(List.of()));

    var result = service.userItems("tenant1", pageable);

    assertThat(result).isNotNull();
    verify(repository).findById("tenant1");
    verify(tenantUserRelevanceRepository).items(pageable);
    verify(tenantUserRelevanceRepository, never()).items("tenant1", pageable);
  }

  @Test
  void userItems_withKeywordSearchesGlobalCandidatesAfterTenantValidation() {
    setAdminRole();
    Tenant tenant = new Tenant();
    tenant.setId("tenant1");
    tenant.setOwnerId("owner1");
    Pageable pageable = Pageable.ofSize(20);
    when(repository.findById("tenant1")).thenReturn(Optional.of(tenant));
    when(tenantUserRelevanceRepository.searchItems("alice", pageable))
        .thenReturn(new PageImpl<>(List.of()));

    var result = service.userItems("tenant1", "alice", pageable);

    assertThat(result).isNotNull();
    verify(repository).findById("tenant1");
    verify(tenantUserRelevanceRepository).searchItems("alice", pageable);
    verify(tenantUserRelevanceRepository, never()).items(pageable);
  }

  // ── authorizedUsers ───────────────────────────────────────────────────────

  @Test
  void authorizedUsers_includesOwnerAndRelevanceUsers() {
    setAdminRole();
    Tenant tenant = new Tenant();
    tenant.setId("tenant1");
    tenant.setOwnerId("owner1");
    when(repository.findById("tenant1")).thenReturn(Optional.of(tenant));
    when(tenantUserRelevanceRepository.authorized("tenant1")).thenReturn(List.of("user2"));

    Collection<String> result = service.authorizedUsers("tenant1");

    assertThat(result).containsExactlyInAnyOrder("user2", "owner1");
  }

  @Test
  void authorizedUsers_throwsAccessDeniedForNonOwnerNonAdmin() {
    setAuthentication("regularUser");
    Tenant tenant = new Tenant();
    tenant.setId("tenant1");
    tenant.setOwnerId("owner1");
    when(repository.findById("tenant1")).thenReturn(Optional.of(tenant));

    assertThatThrownBy(() -> service.authorizedUsers("tenant1"))
        .isInstanceOf(AccessDeniedException.class);
  }

  // ── authorizePackages ─────────────────────────────────────────────────────

  @Test
  void authorizePackages_throwsWhenTenantIdIsBlank() {
    TenantPackagesRelevanceDto dto = new TenantPackagesRelevanceDto();
    dto.setTenantId("  ");
    dto.setPackageCodes(Set.of("pkg.standard"));

    assertThatThrownBy(() -> service.authorizePackages(dto))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("租户ID不能为空");
  }

  @Test
  void authorizePackages_returnsEmptyWhenPackageCodesIsNull() {
    setAdminRole();
    TenantPackagesRelevanceDto dto = new TenantPackagesRelevanceDto();
    dto.setTenantId("tenant1");
    dto.setPackageCodes(null);

    Tenant tenant = new Tenant();
    tenant.setId("tenant1");
    tenant.setOwnerId("admin");
    when(repository.findById("tenant1")).thenReturn(Optional.of(tenant));

    Collection<TenantPackageRelevance> result = service.authorizePackages(dto);

    assertThat(result).isEmpty();
    verify(tenantPackageRelevanceRepository, never()).saveAll(any());
  }

  @Test
  void authorizePackages_returnsEmptyWhenPackageCodesIsEmpty() {
    setAdminRole();
    TenantPackagesRelevanceDto dto = new TenantPackagesRelevanceDto();
    dto.setTenantId("tenant1");
    dto.setPackageCodes(Set.of());

    Tenant tenant = new Tenant();
    tenant.setId("tenant1");
    tenant.setOwnerId("admin");
    when(repository.findById("tenant1")).thenReturn(Optional.of(tenant));

    Collection<TenantPackageRelevance> result = service.authorizePackages(dto);

    assertThat(result).isEmpty();
    verify(tenantPackageRelevanceRepository, never()).saveAll(any());
  }

  @Test
  void authorizePackages_savesRelationsAndRefreshesTenant() {
    setAdminRole();
    TenantPackagesRelevanceDto dto = new TenantPackagesRelevanceDto();
    dto.setTenantId("tenant1");
    dto.setPackageCodes(Set.of("pkg.standard"));

    Tenant tenant = new Tenant();
    tenant.setId("tenant1");
    tenant.setOwnerId("admin");
    when(repository.findById("tenant1")).thenReturn(Optional.of(tenant));

    TenantPackageRelevance saved = new TenantPackageRelevance();
    when(tenantPackageRelevanceRepository.saveAll(any())).thenReturn(List.of(saved));

    Collection<TenantPackageRelevance> result = service.authorizePackages(dto);

    assertThat(result).containsExactly(saved);
    verify(tenantPackageRelevanceRepository).saveAll(any());
    verify(resourceAuthorizationVersionService).refreshTenant("tenant1");
  }

  // ── unauthorizedPackages ──────────────────────────────────────────────────

  @Test
  void unauthorizedPackages_returnsEarlyWhenPackageCodesIsNull() {
    setAdminRole();
    service.unauthorizedPackages("tenant1", null);
    verify(tenantPackageRelevanceRepository, never()).unauthorized(any(), any());
  }

  @Test
  void unauthorizedPackages_returnsEarlyWhenPackageCodesIsEmpty() {
    setAdminRole();
    service.unauthorizedPackages("tenant1", Set.of());
    verify(tenantPackageRelevanceRepository, never()).unauthorized(any(), any());
  }

  @Test
  void unauthorizedPackages_throwsWhenTenantIdIsBlankAfterNormalize() {
    assertThatThrownBy(() -> service.unauthorizedPackages("  ", Set.of("pkg.standard")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("租户ID不能为空");
  }

  @Test
  void unauthorizedPackages_callsRepositoryAndRefreshesVersion() {
    setAdminRole();
    Tenant tenant = new Tenant();
    tenant.setId("tenant1");
    tenant.setOwnerId("admin");
    when(repository.findById("tenant1")).thenReturn(Optional.of(tenant));

    service.unauthorizedPackages("tenant1", Set.of("pkg.standard"));

    verify(tenantPackageRelevanceRepository).unauthorized("tenant1", Set.of("pkg.standard"));
    verify(resourceAuthorizationVersionService).refreshTenant("tenant1");
  }

  @Test
  void authorizedPackages_throwsAccessDeniedForNonOwnerNonAdmin() {
    setAuthentication("regularUser");
    Tenant tenant = new Tenant();
    tenant.setId("tenant1");
    tenant.setOwnerId("owner1");
    when(repository.findById("tenant1")).thenReturn(Optional.of(tenant));

    assertThatThrownBy(() -> service.authorizedPackages("tenant1"))
        .isInstanceOf(AccessDeniedException.class);
  }

  // ── authorizeUsers ────────────────────────────────────────────────────────

  @Test
  void authorizeUsers_returnsEmptyWhenUserIdsEmpty() {
    setAdminRole();
    TenantUsersRelevanceDto dto = new TenantUsersRelevanceDto();
    dto.setTenantId("tenant1");
    dto.setUserIds(Set.of());

    Tenant tenant = new Tenant();
    tenant.setId("tenant1");
    tenant.setOwnerId("admin");
    when(repository.findById("tenant1")).thenReturn(Optional.of(tenant));

    Collection<TenantUserRelevance> result = service.authorizeUsers(dto);

    assertThat(result).isEmpty();
    verify(tenantUserRelevanceRepository, never()).saveAll(any());
  }

  @Test
  void authorizeUsers_savesNewUsersAndRefreshesVersion() {
    setAdminRole();
    TenantUsersRelevanceDto dto = new TenantUsersRelevanceDto();
    dto.setTenantId("tenant1");
    dto.setUserIds(Set.of("user2"));

    Tenant tenant = new Tenant();
    tenant.setId("tenant1");
    tenant.setOwnerId("admin");
    when(repository.findById("tenant1")).thenReturn(Optional.of(tenant));
    when(tenantUserRelevanceRepository.authorized("tenant1")).thenReturn(List.of());
    when(tenantUserRelevanceRepository.existingUserIds(Set.of("user2"))).thenReturn(Set.of("user2"));

    TenantUserRelevance rel = new TenantUserRelevance();
    when(tenantUserRelevanceRepository.saveAll(any())).thenReturn(List.of(rel));

    Collection<TenantUserRelevance> result = service.authorizeUsers(dto);

    assertThat(result).containsExactly(rel);
    verify(resourceAuthorizationVersionService).refreshTenant("tenant1");
  }

  @Test
  void authorizeUsers_returnsEmptyWhenAllUsersAlreadyMembers() {
    setAdminRole();
    TenantUsersRelevanceDto dto = new TenantUsersRelevanceDto();
    dto.setTenantId("tenant1");
    dto.setUserIds(Set.of("user2"));

    Tenant tenant = new Tenant();
    tenant.setId("tenant1");
    tenant.setOwnerId("admin");
    when(repository.findById("tenant1")).thenReturn(Optional.of(tenant));
    when(tenantUserRelevanceRepository.authorized("tenant1")).thenReturn(List.of("user2"));
    when(tenantUserRelevanceRepository.existingUserIds(Set.of("user2"))).thenReturn(Set.of("user2"));

    Collection<TenantUserRelevance> result = service.authorizeUsers(dto);

    assertThat(result).isEmpty();
    verify(tenantUserRelevanceRepository, never()).saveAll(any());
  }

  // ── unauthorizedUsers ─────────────────────────────────────────────────────

  @Test
  void unauthorizedUsers_throwsWhenNotAuthenticated() {
    SecurityContextHolder.clearContext();
    assertThatThrownBy(() -> service.unauthorizedUsers("tenant1", Set.of("user2")))
        .isInstanceOf(Exception.class);
  }

  @Test
  void unauthorizedUsers_throwsWhenOwnerIsInUserIds() {
    setAuthentication("admin");
    setAdminRole();

    Tenant tenant = new Tenant();
    tenant.setId("tenant1");
    tenant.setOwnerId("owner1");
    when(repository.findById("tenant1")).thenReturn(Optional.of(tenant));

    assertThatThrownBy(() -> service.unauthorizedUsers("tenant1", Set.of("owner1", "user2")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("租户所有者不能移出租户成员");
  }

  @Test
  void unauthorizedUsers_returnsEarlyWhenUserIdsIsEmpty() {
    setAuthentication("admin");
    setAdminRole();

    Tenant tenant = new Tenant();
    tenant.setId("tenant1");
    tenant.setOwnerId("owner1");
    when(repository.findById("tenant1")).thenReturn(Optional.of(tenant));

    service.unauthorizedUsers("tenant1", Set.of());

    verify(tenantUserRelevanceRepository, never()).unauthorized(any(), any());
  }

  @Test
  void unauthorizedUsers_removesUsersAndRefreshesVersion() {
    setAdminRole();
    Tenant tenant = new Tenant();
    tenant.setId("tenant1");
    tenant.setOwnerId("owner1");
    when(repository.findById("tenant1")).thenReturn(Optional.of(tenant));

    service.unauthorizedUsers("tenant1", Set.of("user2"));

    verify(tenantUserRelevanceRepository).unauthorized("tenant1", Set.of("user2"));
    verify(resourceAuthorizationVersionService).refreshTenant("tenant1");
  }

  // ── create ────────────────────────────────────────────────────────────────

  @Test
  void create_throwsWhenNotAuthenticated() {
    SecurityContextHolder.clearContext();
    assertThatThrownBy(() -> service.create(new Tenant()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("当前未认证用户");
  }

  @Test
  void create_setsOwnerFromAuthenticationAndSaves() {
    setAdminRole();
    final Tenant entity = new Tenant();

    when(tenantUserRelevanceRepository.existingUserIds(Set.of("admin"))).thenReturn(Set.of("admin"));
    Tenant saved = new Tenant();
    saved.setId("t-new");
    saved.setOwnerId("admin");
    when(repository.save(any())).thenReturn(saved);
    when(tenantUserRelevanceRepository.authorized("t-new")).thenReturn(List.of("admin"));

    Tenant result = service.create(entity);

    assertThat(result.getId()).isEqualTo("t-new");
    verify(repository).save(any());
  }

  @Test
  void create_throwsWhenOwnerDoesNotExist() {
    setAdminRole();
    Tenant entity = new Tenant();

    when(tenantUserRelevanceRepository.existingUserIds(Set.of("admin"))).thenReturn(Set.of());

    assertThatThrownBy(() -> service.create(entity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("用户不存在");
  }

  // ── modifyById ────────────────────────────────────────────────────────────

  @Test
  void modifyById_throwsWhenNotAuthenticated() {
    SecurityContextHolder.clearContext();
    Tenant entity = new Tenant();
    entity.setId("t1");

    assertThatThrownBy(() -> service.modifyById(entity))
        .isInstanceOf(Exception.class);
  }

  @Test
  void modifyById_throwsWhenTenantNotFound() {
    setAdminRole();
    Tenant entity = new Tenant();
    entity.setId("missing");
    when(repository.findById("missing")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.modifyById(entity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("租户不存在");
  }

  @Test
  void modifyById_preservesAuthorizationVersionFromCurrentWhenNull() {
    setAdminRole();
    Tenant current = new Tenant();
    current.setId("t1");
    current.setOwnerId("owner1");
    current.setAuthorizationVersion(3L);
    when(repository.findById("t1")).thenReturn(Optional.of(current));
    stubBaseServiceSchema(detailsProviderService);

    Tenant entity = new Tenant();
    entity.setId("t1");
    entity.setOwnerId("owner1");
    entity.setAuthorizationVersion(null);

    when(tenantUserRelevanceRepository.existingUserIds(Set.of("owner1"))).thenReturn(Set.of("owner1"));
    when(repository.updateById(any())).thenAnswer(inv -> inv.getArgument(0));
    when(tenantUserRelevanceRepository.authorized("t1")).thenReturn(List.of("owner1"));

    Tenant result = service.modifyById(entity);

    assertThat(result.getAuthorizationVersion()).isEqualTo(3L);
  }

  @Test
  void updateCurrentTenantProfile_updatesOssBrandingAndDecoratesOwner() {
    setAuthentication("owner1");
    setTenantContext("t1", "owner1");
    Tenant current = new Tenant();
    current.setId("t1");
    current.setName("Old name");
    current.setOwnerId("owner1");
    current.setTenantType(TenantType.ORGANIZATION);
    when(repository.findById("t1")).thenReturn(Optional.of(current));
    when(repository.hasUser("t1", "owner1")).thenReturn(true);
    when(repository.updateById(any(Tenant.class))).thenAnswer(invocation -> invocation.getArgument(0));
    org.simplepoint.security.entity.User owner = new org.simplepoint.security.entity.User();
    owner.setId("owner1");
    owner.setName("张三");
    owner.setEmail("owner@example.com");
    when(usersService.findAllByIdsForAuthorization(Set.of("owner1"))).thenReturn(List.of(owner));
    TenantProfileUpdateCommand command = new TenantProfileUpdateCommand();
    command.setName("New name");
    command.setDescription("New description");
    command.setLogo("/common/object-storage/images/logo-id");
    command.setBackgroundImage("https://oss.example.com/background.webp");

    Tenant result = service.updateCurrentTenantProfile(command);

    assertThat(result.getName()).isEqualTo("New name");
    assertThat(result.getLogo()).isEqualTo("/common/object-storage/images/logo-id");
    assertThat(result.getBackgroundImage()).isEqualTo("https://oss.example.com/background.webp");
    assertThat(result.getOwnerName()).isEqualTo("张三");
    assertThat(result.getOwnerEmail()).isEqualTo("owner@example.com");
    assertThat(result.getProfileEditable()).isTrue();
  }

  @Test
  void updateCurrentTenantProfile_rejectsInlineImageData() {
    setAuthentication("owner1");
    setTenantContext("t1", "owner1");
    Tenant current = new Tenant();
    current.setId("t1");
    current.setName("Old name");
    current.setOwnerId("owner1");
    current.setTenantType(TenantType.ORGANIZATION);
    when(repository.findById("t1")).thenReturn(Optional.of(current));
    when(repository.hasUser("t1", "owner1")).thenReturn(true);
    when(usersService.findAllByIdsForAuthorization(Set.of("owner1"))).thenReturn(List.of());
    TenantProfileUpdateCommand command = new TenantProfileUpdateCommand();
    command.setName("New name");
    command.setLogo("data:image/png;base64,AAAA");

    assertThatThrownBy(() -> service.updateCurrentTenantProfile(command))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("租户 Logo 必须使用 OSS 图片地址");
  }

  // ── removeByIds ───────────────────────────────────────────────────────────

  @Test
  void removeByIds_returnsEarlyWhenIdsIsNull() {
    setAdminRole();
    service.removeByIds(null);
    verify(repository, never()).deleteByIds(any());
  }

  @Test
  void removeByIds_returnsEarlyWhenIdsIsEmpty() {
    setAdminRole();
    service.removeByIds(List.of());
    verify(repository, never()).deleteByIds(any());
  }

  @Test
  void removeByIds_cleansPackagesAndUsersBeforeDelete() {
    setAdminRole();
    Tenant tenant1 = new Tenant();
    tenant1.setId("t1");
    Tenant tenant2 = new Tenant();
    tenant2.setId("t2");
    when(repository.findAllByIds(List.of("t1", "t2"))).thenReturn(List.of(tenant1, tenant2));
    service.removeByIds(List.of("t1", "t2"));

    verify(tenantPackageRelevanceRepository).deleteAllByTenantIds(List.of("t1", "t2"));
    verify(tenantUserRelevanceRepository).deleteAllByTenantIds(List.of("t1", "t2"));
    verify(repository).deleteByIds(any());
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private void setAuthentication(String username) {
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken(username, null, List.of());
    SecurityContextHolder.getContext().setAuthentication(auth);
  }

  private void setAdminRole() {
    SimpleGrantedAuthority adminAuthority =
        new SimpleGrantedAuthority("ROLE_Administrator");
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken("admin", null, List.of(adminAuthority));
    SecurityContextHolder.getContext().setAuthentication(auth);
  }

  private void setTenantContext(String tenantId, String userId) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(request)
    );
    AuthorizationContext context = new AuthorizationContext();
    context.setUserId(userId);
    context.setAttributes(java.util.Map.of("X-Tenant-Id", tenantId));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }
}
