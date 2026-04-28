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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.plugin.rbac.tenant.api.entity.Package;
import org.simplepoint.plugin.rbac.tenant.api.entity.PackageApplicationRelevance;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.PackageApplicationsRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.repository.PackageApplicationRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.PackageRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;

@ExtendWith(MockitoExtension.class)
class PackageServiceImplTest {

  @Mock
  PackageRepository repository;

  @Mock
  DetailsProviderService detailsProviderService;

  @Mock
  PackageApplicationRelevanceRepository packageApplicationRelevanceRepository;

  @Mock
  TenantPackageRelevanceRepository tenantPackageRelevanceRepository;

  @Mock
  TenantRepository tenantRepository;

  @InjectMocks
  PackageServiceImpl service;

  // ── authorizedApplications ────────────────────────────────────────────────

  @Test
  void authorizedApplications_throwsWhenCodeIsNull() {
    assertThatThrownBy(() -> service.authorizedApplications(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("套餐编码不能为空");
  }

  @Test
  void authorizedApplications_throwsWhenCodeIsBlank() {
    assertThatThrownBy(() -> service.authorizedApplications(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("套餐编码不能为空");
  }

  @Test
  void authorizedApplications_delegatesToRepository() {
    List<String> expected = List.of("app.a", "app.b");
    when(packageApplicationRelevanceRepository.authorized("pkg.code")).thenReturn(expected);

    Collection<String> result = service.authorizedApplications("pkg.code");

    assertThat(result).isEqualTo(expected);
    verify(packageApplicationRelevanceRepository).authorized("pkg.code");
  }

  // ── authorizeApplications ─────────────────────────────────────────────────

  @Test
  void authorizeApplications_throwsWhenPackageCodeIsBlank() {
    PackageApplicationsRelevanceDto dto = new PackageApplicationsRelevanceDto();
    dto.setPackageCode("  ");
    dto.setApplicationCodes(Set.of("app.code"));

    assertThatThrownBy(() -> service.authorizeApplications(dto))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("套餐编码不能为空");
  }

  @Test
  void authorizeApplications_returnsEmptyWhenApplicationCodesIsNull() {
    PackageApplicationsRelevanceDto dto = new PackageApplicationsRelevanceDto();
    dto.setPackageCode("pkg.code");
    dto.setApplicationCodes(null);

    Collection<PackageApplicationRelevance> result = service.authorizeApplications(dto);

    assertThat(result).isEmpty();
    verify(packageApplicationRelevanceRepository, never()).saveAll(any());
  }

  @Test
  void authorizeApplications_returnsEmptyWhenApplicationCodesIsEmpty() {
    PackageApplicationsRelevanceDto dto = new PackageApplicationsRelevanceDto();
    dto.setPackageCode("pkg.code");
    dto.setApplicationCodes(Set.of());

    Collection<PackageApplicationRelevance> result = service.authorizeApplications(dto);

    assertThat(result).isEmpty();
    verify(packageApplicationRelevanceRepository, never()).saveAll(any());
  }

  @Test
  void authorizeApplications_savesRelationsAndRefreshTenants() {
    PackageApplicationsRelevanceDto dto = new PackageApplicationsRelevanceDto();
    dto.setPackageCode("pkg.code");
    dto.setApplicationCodes(Set.of("app.code"));

    PackageApplicationRelevance saved = new PackageApplicationRelevance();
    when(packageApplicationRelevanceRepository.saveAll(any())).thenReturn(List.of(saved));
    when(tenantPackageRelevanceRepository.findTenantIdsByPackageCodes(any())).thenReturn(Set.of());

    Collection<PackageApplicationRelevance> result = service.authorizeApplications(dto);

    assertThat(result).containsExactly(saved);
    verify(packageApplicationRelevanceRepository).saveAll(any());
  }

  // ── unauthorizedApplications ──────────────────────────────────────────────

  @Test
  void unauthorizedApplications_returnsEarlyWhenApplicationCodesIsNull() {
    service.unauthorizedApplications("pkg.code", null);
    verify(packageApplicationRelevanceRepository, never()).unauthorized(any(), any());
  }

  @Test
  void unauthorizedApplications_returnsEarlyWhenApplicationCodesIsEmpty() {
    service.unauthorizedApplications("pkg.code", Set.of());
    verify(packageApplicationRelevanceRepository, never()).unauthorized(any(), any());
  }

  @Test
  void unauthorizedApplications_throwsWhenPackageCodeIsBlankAfterNormalize() {
    assertThatThrownBy(() -> service.unauthorizedApplications("", Set.of("app.code")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("套餐编码不能为空");
  }

  // ── modifyById ────────────────────────────────────────────────────────────

  @Test
  void modifyById_throwsWhenPackageNotFound() {
    Package entity = new Package();
    entity.setId("missing");
    when(repository.findById("missing")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.modifyById(entity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("套餐不存在");
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
}
