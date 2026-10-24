package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.simplepoint.events.api.EventPublisher;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ManagedResourceOfflineHandlerOwnershipTest {
  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withUserConfiguration(ManagedResourceOfflineHandler.class);

  @Test
  void enablingDurableAuditDoesNotLoadAnotherOwnersSubscription() {
    runner.withPropertyValues("simplepoint.events.enabled=true")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(ManagedResourceOfflineHandler.class);
        });
    runner.withPropertyValues("simplepoint.events.enabled=true",
            "simplepoint.rbac.managed-resource.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(ManagedResourceOfflineHandler.class));
  }

  @Test
  void resourceOwnerRequiresEnabledDurableEvents() {
    runner.withPropertyValues("simplepoint.rbac.managed-resource.enabled=true",
            "simplepoint.events.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(ManagedResourceOfflineHandler.class));
  }

  @Test
  void selectedOwnerRegistersItsSubscription() {
    runner.withBean(ManagedTenantResourceServiceImpl.class,
            () -> mock(ManagedTenantResourceServiceImpl.class))
        .withBean(EventPublisher.class, () -> mock(EventPublisher.class))
        .withPropertyValues("simplepoint.events.enabled=true",
            "simplepoint.rbac.managed-resource.enabled=true")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(ManagedResourceOfflineHandler.class);
        });
  }
}
