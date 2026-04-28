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
import org.simplepoint.plugin.rbac.tenant.api.entity.Application;
import org.simplepoint.plugin.rbac.tenant.api.entity.ApplicationFeatureRelevance;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.ApplicationFeaturesRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.repository.ApplicationFeatureRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.ApplicationRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.PackageApplicationRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;

@ExtendWith(MockitoExtension.class)
class ApplicationServiceImplTest {

  @Mock
  ApplicationRepository repository;

  @Mock
  DetailsProviderService detailsProviderService;

  @Mock
  ApplicationFeatureRelevanceRepository applicationFeatureRelevanceRepository;

  @Mock
  PackageApplicationRelevanceRepository packageApplicationRelevanceRepository;

  @Mock
  TenantPackageRelevanceRepository tenantPackageRelevanceRepository;

  @Mock
  TenantRepository tenantRepository;

  @InjectMocks
  ApplicationServiceImpl service;

  // ── authorizedFeatures ────────────────────────────────────────────────────

  @Test
  void authorizedFeatures_throwsWhenCodeIsNull() {
    assertThatThrownBy(() -> service.authorizedFeatures(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("应用编码不能为空");
  }

  @Test
  void authorizedFeatures_throwsWhenCodeIsBlank() {
    assertThatThrownBy(() -> service.authorizedFeatures("  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("应用编码不能为空");
  }

  @Test
  void authorizedFeatures_delegatesToRepository() {
    List<String> expected = List.of("feature.a", "feature.b");
    when(applicationFeatureRelevanceRepository.authorized("app.code")).thenReturn(expected);

    Collection<String> result = service.authorizedFeatures("app.code");

    assertThat(result).isEqualTo(expected);
    verify(applicationFeatureRelevanceRepository).authorized("app.code");
  }

  // ── authorizeFeatures ─────────────────────────────────────────────────────

  @Test
  void authorizeFeatures_throwsWhenApplicationCodeIsBlank() {
    ApplicationFeaturesRelevanceDto dto = new ApplicationFeaturesRelevanceDto();
    dto.setApplicationCode("");
    dto.setFeatureCodes(Set.of("f1"));

    assertThatThrownBy(() -> service.authorizeFeatures(dto))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("应用编码不能为空");
  }

  @Test
  void authorizeFeatures_returnsEmptyWhenFeatureCodesIsNull() {
    ApplicationFeaturesRelevanceDto dto = new ApplicationFeaturesRelevanceDto();
    dto.setApplicationCode("app.code");
    dto.setFeatureCodes(null);

    Collection<ApplicationFeatureRelevance> result = service.authorizeFeatures(dto);

    assertThat(result).isEmpty();
    verify(applicationFeatureRelevanceRepository, never()).saveAll(any());
  }

  @Test
  void authorizeFeatures_returnsEmptyWhenFeatureCodesIsEmpty() {
    ApplicationFeaturesRelevanceDto dto = new ApplicationFeaturesRelevanceDto();
    dto.setApplicationCode("app.code");
    dto.setFeatureCodes(Set.of());

    Collection<ApplicationFeatureRelevance> result = service.authorizeFeatures(dto);

    assertThat(result).isEmpty();
    verify(applicationFeatureRelevanceRepository, never()).saveAll(any());
  }

  @Test
  void authorizeFeatures_savesRelationsAndRefreshTenants() {
    ApplicationFeaturesRelevanceDto dto = new ApplicationFeaturesRelevanceDto();
    dto.setApplicationCode("app.code");
    dto.setFeatureCodes(Set.of("f1"));

    ApplicationFeatureRelevance saved = new ApplicationFeatureRelevance();
    when(applicationFeatureRelevanceRepository.saveAll(any())).thenReturn(List.of(saved));
    when(tenantPackageRelevanceRepository.findTenantIdsByApplicationCodes(any())).thenReturn(Set.of());

    Collection<ApplicationFeatureRelevance> result = service.authorizeFeatures(dto);

    assertThat(result).containsExactly(saved);
    verify(applicationFeatureRelevanceRepository).saveAll(any());
  }

  // ── unauthorizedFeatures ──────────────────────────────────────────────────

  @Test
  void unauthorizedFeatures_returnsEarlyWhenFeatureCodesIsNull() {
    service.unauthorizedFeatures("app.code", null);
    verify(applicationFeatureRelevanceRepository, never()).unauthorized(any(), any());
  }

  @Test
  void unauthorizedFeatures_returnsEarlyWhenFeatureCodesIsEmpty() {
    service.unauthorizedFeatures("app.code", Set.of());
    verify(applicationFeatureRelevanceRepository, never()).unauthorized(any(), any());
  }

  @Test
  void unauthorizedFeatures_throwsWhenApplicationCodeIsBlankAfterNormalize() {
    assertThatThrownBy(() -> service.unauthorizedFeatures("", Set.of("f1")))
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
}
