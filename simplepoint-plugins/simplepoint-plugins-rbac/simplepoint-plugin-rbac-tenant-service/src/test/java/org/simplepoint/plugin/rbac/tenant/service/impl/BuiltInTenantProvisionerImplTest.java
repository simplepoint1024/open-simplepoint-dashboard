package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.rbac.core.api.repository.RoleRepository;
import org.simplepoint.plugin.rbac.core.api.repository.RoleResourceGrantRepository;
import org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes;
import org.simplepoint.plugin.rbac.tenant.api.entity.Application;
import org.simplepoint.plugin.rbac.tenant.api.entity.ApplicationResourceRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.Package;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageRelevance;
import org.simplepoint.plugin.rbac.tenant.api.repository.ApplicationRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.ApplicationResourceRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.PackageRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.service.properties.BuiltInTenantBootstrapProperties;

class BuiltInTenantProvisionerImplTest {

  private ApplicationRepository applicationRepository;
  private PackageRepository packageRepository;
  private TenantRepository tenantRepository;
  private ApplicationResourceRelevanceRepository applicationResourceRepository;
  private TenantPackageRelevanceRepository tenantPackageRepository;
  private RoleRepository roleRepository;
  private RoleResourceGrantRepository roleResourceGrantRepository;
  private BuiltInTenantProvisionerImpl provisioner;

  @BeforeEach
  void setUp() {
    applicationRepository = mock(ApplicationRepository.class);
    packageRepository = mock(PackageRepository.class);
    tenantRepository = mock(TenantRepository.class);
    applicationResourceRepository = mock(ApplicationResourceRelevanceRepository.class);
    tenantPackageRepository = mock(TenantPackageRelevanceRepository.class);
    roleRepository = mock(RoleRepository.class);
    roleResourceGrantRepository = mock(RoleResourceGrantRepository.class);
    provisioner = new BuiltInTenantProvisionerImpl(
        applicationRepository,
        packageRepository,
        tenantRepository,
        applicationResourceRepository,
        tenantPackageRepository,
        roleRepository,
        roleResourceGrantRepository,
        new BuiltInTenantBootstrapProperties()
    );
    when(applicationRepository.findAll(anyMap())).thenAnswer(invocation -> {
      Map<String, String> attributes = invocation.getArgument(0);
      Application application = new Application();
      application.setId("app-" + attributes.get("code"));
      application.setCode(attributes.get("code"));
      return List.of(application);
    });
    when(applicationResourceRepository.authorized(any())).thenReturn(List.of());
    when(tenantRepository.findAll(anyMap())).thenReturn(List.of());
  }

  @Test
  void commonCatalogIsSplitBetweenCoreAndStorageApplications() {
    provisioner.provisionApplicationResources(
        "common",
        Set.of("dashboard.view", "storage.objects.view"),
        Set.of("dashboard.view", "storage.objects.view")
    );

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Iterable<ApplicationResourceRelevance>> captor = ArgumentCaptor.forClass(Iterable.class);
    verify(applicationResourceRepository, org.mockito.Mockito.times(2)).saveAll(captor.capture());
    List<ApplicationResourceRelevance> saved = new ArrayList<>();
    captor.getAllValues().forEach(items -> items.forEach(saved::add));
    assertThat(saved)
        .extracting(ApplicationResourceRelevance::getApplicationCode, ApplicationResourceRelevance::getResourceCode)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(BuiltInTenantCodes.CORE_APPLICATION, "dashboard.view"),
            org.assertj.core.groups.Tuple.tuple(BuiltInTenantCodes.STORAGE_APPLICATION, "storage.objects.view")
        );
  }

  @Test
  void existingApplicationResourcesAreNotDuplicated() {
    when(applicationResourceRepository.authorized(BuiltInTenantCodes.AI_APPLICATION))
        .thenReturn(List.of("ai.knowledge-bases.view"));

    provisioner.provisionApplicationResources(
        "ai",
        Set.of("ai.knowledge-bases.view"),
        Set.of()
    );

    verify(applicationResourceRepository, never()).saveAll(any());
  }

  @Test
  void personalPackageAssignmentIsIdempotent() {
    Package personalPackage = new Package();
    personalPackage.setId("package-personal");
    when(packageRepository.findAll(Map.of("code", BuiltInTenantCodes.PERSONAL_PACKAGE)))
        .thenReturn(List.of(personalPackage));
    when(tenantPackageRepository.authorized("personal-tenant")).thenReturn(List.of());

    provisioner.provisionPersonalTenant("personal-tenant");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Iterable<TenantPackageRelevance>> captor = ArgumentCaptor.forClass(Iterable.class);
    verify(tenantPackageRepository).saveAll(captor.capture());
    Collection<TenantPackageRelevance> saved = new ArrayList<>();
    captor.getValue().forEach(saved::add);
    assertThat(saved).singleElement().satisfies(relevance -> {
      assertThat(relevance.getTenantId()).isEqualTo("personal-tenant");
      assertThat(relevance.getPackageCode()).isEqualTo(BuiltInTenantCodes.PERSONAL_PACKAGE);
    });
  }
}
