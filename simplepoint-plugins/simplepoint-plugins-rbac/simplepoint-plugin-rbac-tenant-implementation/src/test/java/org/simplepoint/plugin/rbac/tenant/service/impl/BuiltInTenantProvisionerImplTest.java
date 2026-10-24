package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes;
import org.simplepoint.plugin.rbac.tenant.api.entity.Application;
import org.simplepoint.plugin.rbac.tenant.api.entity.ApplicationResourceRelevance;
import org.simplepoint.plugin.rbac.tenant.api.repository.ApplicationRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.ApplicationResourceRelevanceRepository;

class BuiltInTenantProvisionerImplTest {

  private ApplicationRepository applicationRepository;
  private ApplicationResourceRelevanceRepository applicationResourceRepository;
  private BuiltInTenantProvisionerImpl provisioner;
  private BuiltInTenantResourcePreparation preparation;

  @BeforeEach
  void setUp() {
    applicationRepository = mock(ApplicationRepository.class);
    applicationResourceRepository = mock(ApplicationResourceRelevanceRepository.class);
    preparation = mock(BuiltInTenantResourcePreparation.class);
    provisioner =
        new BuiltInTenantProvisionerImpl(
            applicationRepository, applicationResourceRepository, preparation);
    when(applicationRepository.findAll(anyMap()))
        .thenAnswer(
            invocation -> {
              Map<String, String> attributes = invocation.getArgument(0);
              Application application = new Application();
              application.setId("app-" + attributes.get("code"));
              application.setCode(attributes.get("code"));
              return List.of(application);
            });
    when(applicationResourceRepository.authorized(any())).thenReturn(List.of());
  }

  @Test
  void commonCatalogIsSplitBetweenCoreAndStorageApplications() {
    provisioner.provisionApplicationResources(
        "common",
        Set.of("dashboard.view", "storage.objects.view"),
        Set.of("dashboard.view", "storage.objects.view"));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Iterable<ApplicationResourceRelevance>> captor =
        ArgumentCaptor.forClass(Iterable.class);
    verify(applicationResourceRepository, org.mockito.Mockito.times(2)).saveAll(captor.capture());
    List<ApplicationResourceRelevance> saved = new ArrayList<>();
    captor.getAllValues().forEach(items -> items.forEach(saved::add));
    assertThat(saved)
        .extracting(
            ApplicationResourceRelevance::getApplicationCode,
            ApplicationResourceRelevance::getResourceCode)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(
                BuiltInTenantCodes.CORE_APPLICATION, "dashboard.view"),
            org.assertj.core.groups.Tuple.tuple(
                BuiltInTenantCodes.STORAGE_APPLICATION, "storage.objects.view"));
  }

  @Test
  void applicationWorkbenchResourcesBelongToTheirOwnApplication() {
    provisioner.provisionApplicationResources(
        "app",
        Set.of("app.workbench.view", "app.templates.view", "app.marketplace.view"),
        Set.of());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Iterable<ApplicationResourceRelevance>> captor =
        ArgumentCaptor.forClass(Iterable.class);
    verify(applicationResourceRepository).saveAll(captor.capture());
    List<ApplicationResourceRelevance> saved = new ArrayList<>();
    captor.getValue().forEach(saved::add);
    assertThat(saved)
        .extracting(ApplicationResourceRelevance::getApplicationCode)
        .containsOnly(BuiltInTenantCodes.APP_APPLICATION);
    assertThat(saved)
        .extracting(ApplicationResourceRelevance::getResourceCode)
        .containsExactlyInAnyOrder(
            "app.workbench.view", "app.templates.view", "app.marketplace.view");
  }

  @Test
  void dnaRuntimeResourcesBelongToTheApplicationWorkbench() {
    provisioner.provisionApplicationResources(
        "dna",
        Set.of("dna.runtime.models.view", "dna.runtime.models.retry", "dna.data-sources.view"),
        Set.of());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Iterable<ApplicationResourceRelevance>> captor =
        ArgumentCaptor.forClass(Iterable.class);
    verify(applicationResourceRepository).saveAll(captor.capture());
    List<ApplicationResourceRelevance> saved = new ArrayList<>();
    captor.getValue().forEach(saved::add);
    assertThat(saved)
        .extracting(ApplicationResourceRelevance::getApplicationCode)
        .containsOnly(BuiltInTenantCodes.APP_APPLICATION);
    assertThat(saved)
        .extracting(ApplicationResourceRelevance::getResourceCode)
        .containsExactlyInAnyOrder("dna.runtime.models.view", "dna.runtime.models.retry");
  }

  @Test
  void existingApplicationResourcesAreNotDuplicated() {
    when(applicationResourceRepository.authorized(BuiltInTenantCodes.AI_APPLICATION))
        .thenReturn(List.of("ai.knowledge-bases.view"));

    provisioner.provisionApplicationResources("ai", Set.of("ai.knowledge-bases.view"), Set.of());

    verify(applicationResourceRepository, never()).saveAll(any());
  }

  @Test
  void unrelatedDeclarationsDoNotCompletePendingTenants() {
    provisioner.provisionApplicationResources("app", Set.of("app.workbench.view"), Set.of());
    verify(preparation, never()).completePending();
  }

  @Test
  void emptyCommonDeclarationStillValidatesPendingReadiness() {
    provisioner.provisionApplicationResources("common", Set.of(), Set.of());
    verify(applicationResourceRepository, never()).saveAll(any());
    verify(preparation).completePending();
  }

  @Test
  void commonRegistrationRechecksPendingPreparationEvenWhenMembershipsAlreadyExist() {
    when(applicationResourceRepository.authorized(BuiltInTenantCodes.CORE_APPLICATION))
        .thenReturn(List.of("dashboard.view"));
    provisioner.provisionApplicationResources("common", Set.of("dashboard.view"), Set.of());
    verify(applicationResourceRepository, never()).saveAll(any());
    verify(preparation).completePending();
  }
}
