package org.simplepoint.plugin.rbac.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.plugin.rbac.tenant.service.impl.ResourceAuthorizationVersionServiceImpl;

class ResourceAuthorizationVersionServiceTest {
  @Test
  void apiContractDoesNotInstallPersistenceInConsumingServices() {
    assertThat(ResourceAuthorizationVersionService.class).isInterface();
    try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
      context.scan("org.simplepoint.plugin.rbac.tenant.api.service");
      context.refresh();
      assertThat(context.getBeansOfType(ResourceAuthorizationVersionService.class)).isEmpty();
      assertThat(context.getBeansOfType(TenantRepository.class)).isEmpty();
    }
  }

  @Test
  void explicitTenantInvalidationDeduplicatesAndBoundsTheWriteCohort() {
    var repository = mock(TenantRepository.class);
    var service = new ResourceAuthorizationVersionServiceImpl(repository);
    service.refreshTenant(" ");
    service.refreshTenants(null);
    verifyNoInteractions(repository);
    var ids = new java.util.ArrayList<String>();
    for (int index = 0; index < 521; index++) {
      ids.add("tenant-" + index);
    }
    ids.addAll(List.copyOf(ids));
    ids.add(null);
    ids.add("");
    service.refreshTenants(ids);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<String>> batches = ArgumentCaptor.forClass(Collection.class);
    org.mockito.Mockito.verify(repository, org.mockito.Mockito.times(6))
        .increaseAuthorizationVersion(batches.capture());
    assertThat(batches.getAllValues())
        .allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(100));
    assertThat(batches.getAllValues().stream().flatMap(Collection::stream).distinct()).hasSize(521);
  }
}
