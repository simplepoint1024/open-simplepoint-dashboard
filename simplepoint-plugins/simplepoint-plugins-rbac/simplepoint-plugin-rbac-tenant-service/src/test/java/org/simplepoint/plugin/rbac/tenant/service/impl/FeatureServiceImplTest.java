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
import org.simplepoint.plugin.auditing.logging.api.service.PermissionChangeLogRemoteService;
import org.simplepoint.plugin.rbac.tenant.api.entity.Feature;
import org.simplepoint.plugin.rbac.tenant.api.entity.FeaturePermissionRelevance;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.FeaturePermissionsRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.repository.ApplicationFeatureRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.FeaturePermissionRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.FeatureRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;

@ExtendWith(MockitoExtension.class)
class FeatureServiceImplTest {

  @Mock
  FeatureRepository repository;

  @Mock
  DetailsProviderService detailsProviderService;

  @Mock
  FeaturePermissionRelevanceRepository featurePermissionRelevanceRepository;

  @Mock
  ApplicationFeatureRelevanceRepository applicationFeatureRelevanceRepository;

  @Mock
  TenantPackageRelevanceRepository tenantPackageRelevanceRepository;

  @Mock
  TenantRepository tenantRepository;

  @Mock
  PermissionChangeLogRemoteService permissionChangeLogRemoteService;

  @InjectMocks
  FeatureServiceImpl service;

  // ── authorizedPermissions ─────────────────────────────────────────────────

  @Test
  void authorizedPermissions_throwsWhenCodeIsNull() {
    assertThatThrownBy(() -> service.authorizedPermissions(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("功能编码不能为空");
  }

  @Test
  void authorizedPermissions_throwsWhenCodeIsBlank() {
    assertThatThrownBy(() -> service.authorizedPermissions("  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("功能编码不能为空");
  }

  @Test
  void authorizedPermissions_delegatesToRepository() {
    List<String> expected = List.of("perm.read", "perm.write");
    when(featurePermissionRelevanceRepository.authorized("feat.code")).thenReturn(expected);

    Collection<String> result = service.authorizedPermissions("feat.code");

    assertThat(result).isEqualTo(expected);
    verify(featurePermissionRelevanceRepository).authorized("feat.code");
  }

  // ── findAllByCodes ────────────────────────────────────────────────────────

  @Test
  void findAllByCodes_returnsEmptyWhenCodesIsNull() {
    Collection<Feature> result = service.findAllByCodes(null);
    assertThat(result).isEmpty();
  }

  @Test
  void findAllByCodes_returnsEmptyWhenCodesIsEmpty() {
    Collection<Feature> result = service.findAllByCodes(List.of());
    assertThat(result).isEmpty();
  }

  @Test
  void findAllByCodes_delegatesToRepository() {
    Feature feature = new Feature();
    feature.setCode("f1");
    when(repository.findAllByCodes(Set.of("f1"))).thenReturn(List.of(feature));

    Collection<Feature> result = service.findAllByCodes(Set.of("f1"));

    assertThat(result).containsExactly(feature);
  }

  // ── authorizePermissions ──────────────────────────────────────────────────

  @Test
  void authorizePermissions_throwsWhenFeatureCodeIsBlank() {
    FeaturePermissionsRelevanceDto dto = new FeaturePermissionsRelevanceDto();
    dto.setFeatureCode("");
    dto.setPermissionAuthority(Set.of("perm.read"));

    assertThatThrownBy(() -> service.authorizePermissions(dto))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("功能编码不能为空");
  }

  @Test
  void authorizePermissions_returnsEmptyWhenPermissionAuthorityIsNull() {
    FeaturePermissionsRelevanceDto dto = new FeaturePermissionsRelevanceDto();
    dto.setFeatureCode("feat.code");
    dto.setPermissionAuthority(null);

    Collection<FeaturePermissionRelevance> result = service.authorizePermissions(dto);

    assertThat(result).isEmpty();
    verify(featurePermissionRelevanceRepository, never()).saveAll(any());
  }

  @Test
  void authorizePermissions_returnsEmptyWhenPermissionAuthorityIsEmpty() {
    FeaturePermissionsRelevanceDto dto = new FeaturePermissionsRelevanceDto();
    dto.setFeatureCode("feat.code");
    dto.setPermissionAuthority(Set.of());

    Collection<FeaturePermissionRelevance> result = service.authorizePermissions(dto);

    assertThat(result).isEmpty();
    verify(featurePermissionRelevanceRepository, never()).saveAll(any());
  }

  @Test
  void authorizePermissions_savesRelationsWhenValid() {
    FeaturePermissionsRelevanceDto dto = new FeaturePermissionsRelevanceDto();
    dto.setFeatureCode("feat.code");
    dto.setPermissionAuthority(Set.of("perm.read"));

    FeaturePermissionRelevance saved = new FeaturePermissionRelevance();
    when(featurePermissionRelevanceRepository.saveAll(any())).thenReturn(List.of(saved));
    when(tenantPackageRelevanceRepository.findTenantIdsByFeatureCodes(any())).thenReturn(Set.of());
    // No auth context -> recordPermissionChange is a no-op (userId is null check)

    Collection<FeaturePermissionRelevance> result = service.authorizePermissions(dto);

    assertThat(result).containsExactly(saved);
    verify(featurePermissionRelevanceRepository).saveAll(any());
  }

  // ── unauthorizedPermissions ───────────────────────────────────────────────

  @Test
  void unauthorizedPermissions_returnsEarlyWhenPermissionAuthorityIsNull() {
    service.unauthorizedPermissions("feat.code", null);
    verify(featurePermissionRelevanceRepository, never()).unauthorized(any(), any());
  }

  @Test
  void unauthorizedPermissions_returnsEarlyWhenPermissionAuthorityIsEmpty() {
    service.unauthorizedPermissions("feat.code", Set.of());
    verify(featurePermissionRelevanceRepository, never()).unauthorized(any(), any());
  }

  @Test
  void unauthorizedPermissions_throwsWhenFeatureCodeIsBlankAfterNormalize() {
    assertThatThrownBy(() -> service.unauthorizedPermissions("  ", Set.of("perm.read")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("功能编码不能为空");
  }

  // ── modifyById ────────────────────────────────────────────────────────────

  @Test
  void modifyById_throwsWhenFeatureNotFound() {
    Feature entity = new Feature();
    entity.setId("missing");
    when(repository.findById("missing")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.modifyById(entity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("功能不存在");
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
