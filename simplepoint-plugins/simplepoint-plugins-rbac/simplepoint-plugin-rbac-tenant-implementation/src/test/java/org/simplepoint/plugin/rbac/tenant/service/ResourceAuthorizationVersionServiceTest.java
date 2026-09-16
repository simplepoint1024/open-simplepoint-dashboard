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
import org.simplepoint.plugin.rbac.core.api.repository.RoleResourceGrantRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;

@ExtendWith(MockitoExtension.class)
class ResourceAuthorizationVersionServiceTest {

  @Mock
  TenantRepository tenantRepository;

  @Mock
  TenantPackageRelevanceRepository tenantPackageRelevanceRepository;

  @Mock
  RoleResourceGrantRepository roleResourceGrantRepository;

  @InjectMocks
  ResourceAuthorizationVersionService service;

  @Test
  void refreshByApplicationCodes_refreshesResolvedTenantIds() {
    when(tenantPackageRelevanceRepository.findTenantIdsByApplicationCodes(Set.of("common")))
        .thenReturn(Set.of("tenant1", "tenant2"));

    service.refreshByApplicationCodes(Set.of("common"));

    verify(tenantRepository).increaseAuthorizationVersion(Set.of("tenant1", "tenant2"));
  }

  @Test
  void findAffectedTenantIdsByResourceCodes_mergesRoleGrantsAndPackageResources() {
    when(roleResourceGrantRepository.findTenantIdsByResourceCodes(Set.of("resources.view")))
        .thenReturn(Set.of("tenant1"));
    when(tenantPackageRelevanceRepository.findTenantIdsByResourceCodes(Set.of("resources.view")))
        .thenReturn(Set.of("tenant2"));

    Set<String> result = service.findAffectedTenantIdsByResourceCodes(Set.of("resources.view"));

    assertThat(result).containsExactlyInAnyOrder("tenant1", "tenant2");
  }
}
