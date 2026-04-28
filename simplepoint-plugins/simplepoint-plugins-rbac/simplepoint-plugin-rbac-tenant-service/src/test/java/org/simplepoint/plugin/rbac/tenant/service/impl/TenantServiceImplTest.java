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
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.TenantPackagesRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.TenantUsersRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantUserRelevanceRepository;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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

  // ── getCurrentUserTenants ─────────────────────────────────────────────────

  @Test
  void getCurrentUserTenants_throwsWhenNotAuthenticated() {
    SecurityContextHolder.clearContext();
    assertThatThrownBy(() -> service.getCurrentUserTenants())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("当前未认证用户");
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
    // "default" is returned immediately by resolveTenantId without calling getTenantsByUserId
    String contextId = service.calculatePermissionContextId("default");

    assertThat(contextId).isNotBlank().hasSize(64); // SHA-256 hex is 64 chars
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

  // ── unauthorizedUsers ─────────────────────────────────────────────────────

  @Test
  void unauthorizedUsers_throwsWhenNotAuthenticated() {
    SecurityContextHolder.clearContext();
    // Requires authenticated user to check ownership
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
    org.springframework.security.core.authority.SimpleGrantedAuthority adminAuthority =
        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_Administrator");
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken("admin", null, List.of(adminAuthority));
    SecurityContextHolder.getContext().setAuthentication(auth);
  }
}
