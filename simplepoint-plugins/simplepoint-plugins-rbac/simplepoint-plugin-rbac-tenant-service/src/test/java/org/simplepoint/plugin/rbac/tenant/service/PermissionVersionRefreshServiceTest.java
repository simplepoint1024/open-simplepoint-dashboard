package org.simplepoint.plugin.rbac.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.plugin.rbac.core.api.repository.RolePermissionsRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.FeaturePermissionRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.PermissionVersionRefreshService;

@ExtendWith(MockitoExtension.class)
class PermissionVersionRefreshServiceTest {

  @Mock
  TenantRepository tenantRepository;

  @Mock
  TenantPackageRelevanceRepository tenantPackageRelevanceRepository;

  @Mock
  FeaturePermissionRelevanceRepository featurePermissionRelevanceRepository;

  @Mock
  RolePermissionsRelevanceRepository rolePermissionsRelevanceRepository;

  @InjectMocks
  PermissionVersionRefreshService service;

  @Test
  void refreshByFeatureCodes_refreshesResolvedTenantIds() {
    when(tenantPackageRelevanceRepository.findTenantIdsByFeatureCodes(Set.of("feature1")))
        .thenReturn(Set.of("tenant1", "tenant2"));

    service.refreshByFeatureCodes(Set.of("feature1"));

    verify(tenantRepository).increasePermissionVersion(Set.of("tenant1", "tenant2"));
  }

  @Test
  void findAffectedTenantIdsByPermissionAuthorities_mergesRoleFeatureAndPublicTenants() {
    when(rolePermissionsRelevanceRepository.findTenantIdsByPermissionAuthorities(Set.of("perm.a")))
        .thenReturn(Set.of("tenant1"));
    when(featurePermissionRelevanceRepository.findFeatureCodesByPermissionAuthorities(Set.of("perm.a")))
        .thenReturn(Set.of("feature1"));
    when(tenantPackageRelevanceRepository.findTenantIdsByFeatureCodes(Set.of("feature1")))
        .thenReturn(Set.of("tenant2"));
    when(featurePermissionRelevanceRepository.findPermissionAuthoritiesByPublicAccessFeatures())
        .thenReturn(Set.of("perm.a"));
    when(tenantRepository.findAllIds()).thenReturn(Set.of("tenant1", "tenant2", "tenant3"));

    Set<String> result = service.findAffectedTenantIdsByPermissionAuthorities(Set.of("perm.a"));

    assertThat(result).containsExactlyInAnyOrder("tenant1", "tenant2", "tenant3");
  }
}
