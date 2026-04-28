package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantUserRelevance;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.TenantPackagesRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.TenantUsersRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantUserRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.vo.NamedTenantVo;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

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

  @InjectMocks
  TenantServiceImpl service;

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  // ── getTenantsByUserId ────────────────────────────────────────────────────

  @Test
  void getTenantsByUserId_delegatesToRepository() {
    NamedTenantVo vo = new NamedTenantVo("t1", "Tenant One");
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
    NamedTenantVo vo = new NamedTenantVo("t1", "Tenant One");
    when(repository.getTenantsByUserId("user1")).thenReturn(Set.of(vo));

    Set<NamedTenantVo> result = service.getCurrentUserTenants();

    assertThat(result).containsExactly(vo);
  }

  // ── calculatePermissionContextId ──────────────────────────────────────────

  @Test
  void calculatePermissionContextId_throwsWhenNotAuthenticated() {
    SecurityContextHolder.clearContext();
    assertThatThrownBy(() -> service.calculatePermissionContextId("t1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("当前未认证用户");
  }

  @Test
  void calculatePermissionContextId_returnsHashForDefaultTenant() {
    setAuthentication("user1");
    String contextId = service.calculatePermissionContextId("default");

    assertThat(contextId).isNotBlank().hasSize(64); // SHA-256 hex is 64 chars
  }

  @Test
  void calculatePermissionContextId_withNonDefaultTenantAndVersion() {
    setAuthentication("user1");
    when(repository.getTenantPermissionVersion("tenant1")).thenReturn(5L);

    String contextId = service.calculatePermissionContextId("tenant1");

    assertThat(contextId).isNotBlank().hasSize(64);
    verify(repository).getTenantPermissionVersion("tenant1");
  }

  @Test
  void calculatePermissionContextId_withNullPermissionVersion() {
    setAuthentication("user1");
    when(repository.getTenantPermissionVersion("tenant1")).thenReturn(null);

    String contextId = service.calculatePermissionContextId("tenant1");

    assertThat(contextId).isNotBlank().hasSize(64);
  }

  @Test
  void calculatePermissionContextId_resolvesToFirstTenantWhenBlankTenantId() {
    setAuthentication("user1");
    NamedTenantVo vo = new NamedTenantVo("tenant-a", "Alpha");
    when(repository.getTenantsByUserId("user1")).thenReturn(Set.of(vo));
    when(repository.getTenantPermissionVersion("tenant-a")).thenReturn(1L);

    String contextId = service.calculatePermissionContextId(null);

    assertThat(contextId).isNotBlank().hasSize(64);
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
    TenantPackagesRelevanceDto dto = new TenantPackagesRelevanceDto();
    dto.setTenantId("tenant1");
    dto.setPackageCodes(null);

    Collection<TenantPackageRelevance> result = service.authorizePackages(dto);

    assertThat(result).isEmpty();
    verify(tenantPackageRelevanceRepository, never()).saveAll(any());
  }

  @Test
  void authorizePackages_returnsEmptyWhenPackageCodesIsEmpty() {
    TenantPackagesRelevanceDto dto = new TenantPackagesRelevanceDto();
    dto.setTenantId("tenant1");
    dto.setPackageCodes(Set.of());

    Collection<TenantPackageRelevance> result = service.authorizePackages(dto);

    assertThat(result).isEmpty();
    verify(tenantPackageRelevanceRepository, never()).saveAll(any());
  }

  @Test
  void authorizePackages_savesRelationsAndRefreshesTenant() {
    TenantPackagesRelevanceDto dto = new TenantPackagesRelevanceDto();
    dto.setTenantId("tenant1");
    dto.setPackageCodes(Set.of("pkg.standard"));

    TenantPackageRelevance saved = new TenantPackageRelevance();
    when(tenantPackageRelevanceRepository.saveAll(any())).thenReturn(List.of(saved));

    Collection<TenantPackageRelevance> result = service.authorizePackages(dto);

    assertThat(result).containsExactly(saved);
    verify(tenantPackageRelevanceRepository).saveAll(any());
    verify(repository).increasePermissionVersion(Set.of("tenant1"));
  }

  // ── unauthorizedPackages ──────────────────────────────────────────────────

  @Test
  void unauthorizedPackages_returnsEarlyWhenPackageCodesIsNull() {
    service.unauthorizedPackages("tenant1", null);
    verify(tenantPackageRelevanceRepository, never()).unauthorized(any(), any());
  }

  @Test
  void unauthorizedPackages_returnsEarlyWhenPackageCodesIsEmpty() {
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
    service.unauthorizedPackages("tenant1", Set.of("pkg.standard"));

    verify(tenantPackageRelevanceRepository).unauthorized("tenant1", Set.of("pkg.standard"));
    verify(repository).increasePermissionVersion(Set.of("tenant1"));
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
    verify(repository).increasePermissionVersion(Set.of("tenant1"));
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
    verify(repository).increasePermissionVersion(Set.of("tenant1"));
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
    setAuthentication("user1");
    Tenant entity = new Tenant();

    when(tenantUserRelevanceRepository.existingUserIds(Set.of("user1"))).thenReturn(Set.of("user1"));
    Tenant saved = new Tenant();
    saved.setId("t-new");
    saved.setOwnerId("user1");
    when(repository.save(any())).thenReturn(saved);
    when(tenantUserRelevanceRepository.authorized("t-new")).thenReturn(List.of("user1"));

    Tenant result = service.create(entity);

    assertThat(result.getId()).isEqualTo("t-new");
    verify(repository).save(any());
  }

  @Test
  void create_throwsWhenOwnerDoesNotExist() {
    setAuthentication("user1");
    Tenant entity = new Tenant();

    when(tenantUserRelevanceRepository.existingUserIds(Set.of("user1"))).thenReturn(Set.of());

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
    when(repository.findById("t1")).thenReturn(Optional.of(new Tenant()));

    assertThatThrownBy(() -> service.modifyById(entity))
        .isInstanceOf(Exception.class);
  }

  @Test
  void modifyById_throwsWhenTenantNotFound() {
    setAuthentication("admin");
    Tenant entity = new Tenant();
    entity.setId("missing");
    when(repository.findById("missing")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.modifyById(entity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("租户不存在");
  }

  @Test
  void modifyById_preservesPermissionVersionFromCurrentWhenNull() {
    setAuthentication("owner1");
    Tenant current = new Tenant();
    current.setId("t1");
    current.setOwnerId("owner1");
    current.setPermissionVersion(3L);
    // First call: TenantServiceImpl reads current version; second call: BaseServiceImpl skips field-merge (avoids FormSchemaGenerator init)
    when(repository.findById("t1")).thenReturn(Optional.of(current), Optional.empty());

    Tenant entity = new Tenant();
    entity.setId("t1");
    entity.setOwnerId("owner1");
    entity.setPermissionVersion(null);

    when(tenantUserRelevanceRepository.existingUserIds(Set.of("owner1"))).thenReturn(Set.of("owner1"));
    when(repository.updateById(any())).thenAnswer(inv -> inv.getArgument(0));
    when(tenantUserRelevanceRepository.authorized("t1")).thenReturn(List.of("owner1"));

    Tenant result = service.modifyById(entity);

    assertThat(result.getPermissionVersion()).isEqualTo(3L);
  }

  // ── removeByIds ───────────────────────────────────────────────────────────

  @Test
  void removeByIds_returnsEarlyWhenIdsIsNull() {
    service.removeByIds(null);
    verify(repository, never()).deleteByIds(any());
  }

  @Test
  void removeByIds_returnsEarlyWhenIdsIsEmpty() {
    service.removeByIds(List.of());
    verify(repository, never()).deleteByIds(any());
  }

  @Test
  void removeByIds_cleansPackagesAndUsersBeforeDelete() {
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
}
