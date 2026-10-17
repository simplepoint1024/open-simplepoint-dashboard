package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.resource.api.repository.ResourceRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ManagedTenantResourceProviderBoundaryTest {
  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withBean(EntityManager.class, () -> mock(EntityManager.class))
      .withBean(ResourceRepository.class, () -> mock(ResourceRepository.class))
      .withBean(ResourceAuthorizationVersionService.class, () -> mock(ResourceAuthorizationVersionService.class))
      .withBean(ManagedTenantResourceCallerGuard.class, () -> mock(ManagedTenantResourceCallerGuard.class))
      .withUserConfiguration(ManagedTenantResourceServiceImpl.class);

  @Test
  void importingTenantImplementationDoesNotExportManagedResourceWrites() {
    runner.run(context -> assertThat(context).doesNotHaveBean(ManagedTenantResourceServiceImpl.class));
    runner.withPropertyValues("simplepoint.rbac.managed-resource.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(ManagedTenantResourceServiceImpl.class));
  }

  @Test
  void owningServiceExplicitlyEnablesTheProvider() {
    runner.withPropertyValues("simplepoint.rbac.managed-resource.enabled=true")
        .run(context -> assertThat(context).hasSingleBean(ManagedTenantResourceServiceImpl.class));
  }
}
