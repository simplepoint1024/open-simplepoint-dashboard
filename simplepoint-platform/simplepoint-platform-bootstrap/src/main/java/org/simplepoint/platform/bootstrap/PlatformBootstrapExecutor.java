package org.simplepoint.platform.bootstrap;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.platform.bootstrap.properties.PlatformBootstrapProperties;
import org.simplepoint.platform.bootstrap.service.PlatformContributionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * Executes platform bootstrap contributions.
 */
@Slf4j
public final class PlatformBootstrapExecutor implements ApplicationRunner {

  private final String serviceName;

  private final ObjectProvider<PlatformContributionService> contributionServiceProvider;

  private final Set<PlatformBootstrapContribution> contributionProviders;

  private final PlatformBootstrapProperties properties;

  /**
   * Creates a platform bootstrap executor.
   *
   * @param serviceName                 the current service name
   * @param contributionServiceProvider contribution service provider
   * @param contributionProviders       contribution providers
   * @param properties                  bootstrap properties
   */
  public PlatformBootstrapExecutor(
      String serviceName,
      ObjectProvider<PlatformContributionService> contributionServiceProvider,
      Set<PlatformBootstrapContribution> contributionProviders,
      PlatformBootstrapProperties properties
  ) {
    this.serviceName = serviceName;
    this.contributionServiceProvider = contributionServiceProvider;
    this.contributionProviders = contributionProviders == null ? Set.of() : Set.copyOf(contributionProviders);
    this.properties = properties;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (properties == null || !properties.isEnabled()) {
      log.info("Platform bootstrap is disabled");
      return;
    }
    if (!properties.isServiceEnabled(serviceName)) {
      log.debug("Platform bootstrap is disabled for service: {}", serviceName);
      return;
    }
    List<BootstrapContribution> contributions = contributionProviders.stream()
        .map(PlatformBootstrapContribution::contribution)
        .filter(Objects::nonNull)
        .peek(this::validate)
        .sorted(Comparator.comparingInt(BootstrapContribution::order)
            .thenComparing(BootstrapContribution::moduleCode)
            .thenComparing(BootstrapContribution::contributionType)
            .thenComparing(BootstrapContribution::contributionKey))
        .toList();
    if (contributions.isEmpty()) {
      log.debug("No platform bootstrap contributions found for service: {}", serviceName);
      return;
    }

    PlatformContributionService contributionService = contributionServiceProvider.getIfAvailable();
    if (contributionService == null) {
      handleUnavailableRegistry();
      return;
    }

    for (BootstrapContribution contribution : contributions) {
      executeContribution(contributionService, contribution);
    }
  }

  private void executeContribution(
      PlatformContributionService contributionService,
      BootstrapContribution contribution
  ) {
    String key = contribution.contributionKey();
    if (!properties.isContributionEnabled(contribution)) {
      log.info("Platform bootstrap contribution is disabled: {}", key);
      return;
    }
    if (!Boolean.TRUE.equals(contributionService.shouldApply(
        serviceName,
        contribution.moduleCode(),
        contribution.contributionType(),
        contribution.contributionKey(),
        contribution.version(),
        contribution.checksum()
    ))) {
      log.debug("Platform bootstrap contribution is already applied: {}", key);
      return;
    }

    try {
      contributionService.markRunning(
          serviceName,
          contribution.moduleCode(),
          contribution.contributionType(),
          contribution.contributionKey(),
          contribution.version(),
          contribution.checksum()
      );
      log.info("Applying platform bootstrap contribution: {}", key);
      contribution.action().run();
      contributionService.markApplied(
          serviceName,
          contribution.moduleCode(),
          contribution.contributionType(),
          contribution.contributionKey(),
          contribution.version(),
          contribution.checksum()
      );
      log.info("Applied platform bootstrap contribution: {}", key);
    } catch (Exception ex) {
      contributionService.markFailed(
          serviceName,
          contribution.moduleCode(),
          contribution.contributionType(),
          contribution.contributionKey(),
          contribution.version(),
          contribution.checksum(),
          ex.getMessage()
      );
      if (properties.isFailFast()) {
        throw new IllegalStateException("Platform bootstrap contribution failed: " + key, ex);
      }
      log.warn("Platform bootstrap contribution failed: {}", key, ex);
    }
  }

  private void handleUnavailableRegistry() {
    String message = "PlatformContributionService bean not available";
    if (properties.isFailFast()) {
      throw new IllegalStateException(message);
    }
    log.warn("{}, skipping platform bootstrap contributions", message);
  }

  private void validate(BootstrapContribution contribution) {
    requireText(contribution.moduleCode(), "moduleCode");
    requireText(contribution.contributionType(), "contributionType");
    requireText(contribution.contributionKey(), "contributionKey");
    requireText(contribution.version(), "version");
    requireText(contribution.checksum(), "checksum");
    if (contribution.action() == null) {
      throw new IllegalArgumentException("Bootstrap contribution action must not be null");
    }
  }

  private void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Bootstrap contribution " + field + " must not be blank");
    }
  }
}
