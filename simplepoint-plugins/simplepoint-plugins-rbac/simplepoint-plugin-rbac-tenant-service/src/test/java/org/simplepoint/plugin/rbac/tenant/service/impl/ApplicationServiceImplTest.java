package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.plugin.rbac.tenant.api.entity.Application;
import org.simplepoint.plugin.rbac.tenant.api.entity.ApplicationResourceRelevance;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.ApplicationResourcesRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.repository.ApplicationResourceRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.ApplicationRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.PackageApplicationRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;

@ExtendWith(MockitoExtension.class)
class ApplicationServiceImplTest {

  @Mock
  ApplicationRepository repository;

  @Mock
  DetailsProviderService detailsProviderService;

  @Mock
  ApplicationResourceRelevanceRepository applicationResourceRelevanceRepository;

  @Mock
  PackageApplicationRelevanceRepository packageApplicationRelevanceRepository;

  @Mock
  TenantPackageRelevanceRepository tenantPackageRelevanceRepository;

  @Mock
  TenantRepository tenantRepository;

  @Mock
  ResourceAuthorizationVersionService resourceAuthorizationVersionService;

  @InjectMocks
  ApplicationServiceImpl service;

  MockedStatic<AuthorizationContextHolder> contextHolder;

  @BeforeEach
  void setUpContext() {
    contextHolder = org.mockito.Mockito.mockStatic(AuthorizationContextHolder.class);
    contextHolder.when(AuthorizationContextHolder::getContext).thenReturn(platformAdminContext());
  }

  @AfterEach
  void tearDownContext() {
    contextHolder.close();
  }

  // ── authorizedResources ───────────────────────────────────────────────────

  @Test
  void authorizedResources_throwsWhenCodeIsNull() {
    assertThatThrownBy(() -> service.authorizedResources(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("应用编码不能为空");
  }

  @Test
  void authorizedResources_throwsWhenCodeIsBlank() {
    assertThatThrownBy(() -> service.authorizedResources("  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("应用编码不能为空");
  }

  @Test
  void authorizedResources_delegatesToRepository() {
    List<String> expected = List.of("resources.a", "resources.b");
    when(applicationResourceRelevanceRepository.authorized("app.code")).thenReturn(expected);

    Collection<String> result = service.authorizedResources("app.code");

    assertThat(result).isEqualTo(expected);
    verify(applicationResourceRelevanceRepository).authorized("app.code");
  }

  // ── authorizeResources ────────────────────────────────────────────────────

  @Test
  void authorizeResources_throwsWhenApplicationCodeIsBlank() {
    ApplicationResourcesRelevanceDto dto = new ApplicationResourcesRelevanceDto();
    dto.setApplicationCode("");
    dto.setResourceCodes(Set.of("r1"));

    assertThatThrownBy(() -> service.authorizeResources(dto))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("应用编码不能为空");
  }

  @Test
  void authorizeResources_returnsEmptyWhenResourceCodesIsNull() {
    ApplicationResourcesRelevanceDto dto = new ApplicationResourcesRelevanceDto();
    dto.setApplicationCode("app.code");
    dto.setResourceCodes(null);

    Collection<ApplicationResourceRelevance> result = service.authorizeResources(dto);

    assertThat(result).isEmpty();
    verify(applicationResourceRelevanceRepository, never()).saveAll(any());
  }

  @Test
  void authorizeResources_returnsEmptyWhenResourceCodesIsEmpty() {
    ApplicationResourcesRelevanceDto dto = new ApplicationResourcesRelevanceDto();
    dto.setApplicationCode("app.code");
    dto.setResourceCodes(Set.of());

    Collection<ApplicationResourceRelevance> result = service.authorizeResources(dto);

    assertThat(result).isEmpty();
    verify(applicationResourceRelevanceRepository, never()).saveAll(any());
  }

  @Test
  void authorizeResources_savesRelationsAndRefreshTenants() {
    ApplicationResourcesRelevanceDto dto = new ApplicationResourcesRelevanceDto();
    dto.setApplicationCode("app.code");
    dto.setResourceCodes(Set.of("r1"));

    ApplicationResourceRelevance saved = new ApplicationResourceRelevance();
    when(applicationResourceRelevanceRepository.saveAll(any())).thenReturn(List.of(saved));

    Collection<ApplicationResourceRelevance> result = service.authorizeResources(dto);

    assertThat(result).containsExactly(saved);
    verify(applicationResourceRelevanceRepository).saveAll(any());
  }

  // ── unauthorizedResources ─────────────────────────────────────────────────

  @Test
  void unauthorizedResources_returnsEarlyWhenResourceCodesIsNull() {
    service.unauthorizedResources("app.code", null);
    verify(applicationResourceRelevanceRepository, never()).unauthorized(any(), any());
  }

  @Test
  void unauthorizedResources_returnsEarlyWhenResourceCodesIsEmpty() {
    service.unauthorizedResources("app.code", Set.of());
    verify(applicationResourceRelevanceRepository, never()).unauthorized(any(), any());
  }

  @Test
  void unauthorizedResources_throwsWhenApplicationCodeIsBlankAfterNormalize() {
    assertThatThrownBy(() -> service.unauthorizedResources("", Set.of("r1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("应用编码不能为空");
  }

  // ── modifyById ────────────────────────────────────────────────────────────

  @Test
  void modifyById_throwsWhenApplicationNotFound() {
    Application entity = new Application();
    entity.setId("missing");
    when(repository.findById("missing")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.modifyById(entity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("应用不存在");
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
  void authorizeResources_rejectsTenantContext() {
    contextHolder.when(AuthorizationContextHolder::getContext).thenReturn(tenantContext());
    ApplicationResourcesRelevanceDto dto = new ApplicationResourcesRelevanceDto();
    dto.setApplicationCode("app.code");
    dto.setResourceCodes(Set.of("resources.view"));

    assertThatThrownBy(() -> service.authorizeResources(dto))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  private static AuthorizationContext platformAdminContext() {
    AuthorizationContext ctx = new AuthorizationContext();
    ctx.setIsAdministrator(true);
    ctx.setScopeType(AuthorizationScopeType.PLATFORM);
    return ctx;
  }

  private static AuthorizationContext tenantContext() {
    AuthorizationContext ctx = new AuthorizationContext();
    ctx.setIsAdministrator(false);
    ctx.setScopeType(AuthorizationScopeType.TENANT);
    return ctx;
  }
}
