package org.simplepoint.platform.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.simplepoint.platform.bootstrap.properties.PlatformBootstrapProperties;
import org.simplepoint.platform.bootstrap.service.PlatformContributionService;
import org.springframework.beans.factory.ObjectProvider;

class PlatformBootstrapExecutorTest {

  @Test
  @SuppressWarnings("unchecked")
  void run_shouldApply_executesContributionAndMarksApplied() {
    final PlatformContributionService contributionService = mock(PlatformContributionService.class);
    final ObjectProvider<PlatformContributionService> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(contributionService);
    final AtomicBoolean executed = new AtomicBoolean(false);
    final BootstrapContribution contribution = BootstrapContribution.versioned(
        "rbac-core",
        "system",
        "system-user",
        "1",
        100,
        () -> executed.set(true)
    );
    when(contributionService.shouldApply(
        "common",
        contribution.moduleCode(),
        contribution.contributionType(),
        contribution.contributionKey(),
        contribution.version(),
        contribution.checksum()
    )).thenReturn(Boolean.TRUE);

    final PlatformBootstrapExecutor executor = new PlatformBootstrapExecutor(
        "common",
        provider,
        Set.of(() -> contribution),
        new PlatformBootstrapProperties()
    );

    executor.run(null);

    assertThat(executed).isTrue();
    verify(contributionService).markRunning(
        "common",
        contribution.moduleCode(),
        contribution.contributionType(),
        contribution.contributionKey(),
        contribution.version(),
        contribution.checksum()
    );
    verify(contributionService).markApplied(
        "common",
        contribution.moduleCode(),
        contribution.contributionType(),
        contribution.contributionKey(),
        contribution.version(),
        contribution.checksum()
    );
  }

  @Test
  @SuppressWarnings("unchecked")
  void run_alreadyApplied_skipsContribution() {
    final PlatformContributionService contributionService = mock(PlatformContributionService.class);
    final ObjectProvider<PlatformContributionService> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(contributionService);
    final AtomicBoolean executed = new AtomicBoolean(false);
    final BootstrapContribution contribution = BootstrapContribution.versioned(
        "i18n",
        "messages",
        "i18n-messages",
        "1",
        200,
        () -> executed.set(true)
    );
    when(contributionService.shouldApply(
        "common",
        contribution.moduleCode(),
        contribution.contributionType(),
        contribution.contributionKey(),
        contribution.version(),
        contribution.checksum()
    )).thenReturn(Boolean.FALSE);

    final PlatformBootstrapExecutor executor = new PlatformBootstrapExecutor(
        "common",
        provider,
        Set.of(() -> contribution),
        new PlatformBootstrapProperties()
    );

    executor.run(null);

    assertThat(executed).isFalse();
    verify(contributionService, never()).markRunning(
        "common",
        contribution.moduleCode(),
        contribution.contributionType(),
        contribution.contributionKey(),
        contribution.version(),
        contribution.checksum()
    );
  }
}
