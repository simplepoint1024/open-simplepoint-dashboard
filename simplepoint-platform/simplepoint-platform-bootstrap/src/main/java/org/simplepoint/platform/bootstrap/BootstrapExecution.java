package org.simplepoint.platform.bootstrap;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.simplepoint.platform.bootstrap.service.PlatformContributionService;

/** Shared execution semantics for legacy hosts and module-owned bootstrap. */
final class BootstrapExecution {
  private BootstrapExecution() {}

  static List<BootstrapContribution> prepare(Collection<BootstrapContribution> contributions) {
    var identities = new HashSet<List<String>>();
    return contributions.stream()
        .filter(Objects::nonNull)
        .peek(
            contribution -> {
              text(contribution.moduleCode(), 128, "moduleCode");
              text(contribution.contributionType(), 64, "contributionType");
              text(contribution.contributionKey(), 160, "contributionKey");
              text(contribution.version(), 64, "version");
              text(contribution.checksum(), 256, "checksum");
              if (contribution.action() == null) {
                throw new IllegalArgumentException(
                    "Bootstrap contribution action must not be null");
              }
              if (!identities.add(
                  List.of(
                      contribution.moduleCode(),
                      contribution.contributionType(),
                      contribution.contributionKey()))) {
                throw new IllegalArgumentException(
                    "DUPLICATE_BOOTSTRAP_CONTRIBUTION: " + contribution.contributionKey());
              }
            })
        .sorted(
            Comparator.comparingInt(BootstrapContribution::order)
                .thenComparing(BootstrapContribution::moduleCode)
                .thenComparing(BootstrapContribution::contributionType)
                .thenComparing(BootstrapContribution::contributionKey))
        .toList();
  }

  static void apply(
      String scope, BootstrapContribution contribution, PlatformContributionService registry)
      throws Exception {
    Boolean required =
        registry.shouldApply(
            scope,
            contribution.moduleCode(),
            contribution.contributionType(),
            contribution.contributionKey(),
            contribution.version(),
            contribution.checksum());
    if (required == null) {
      throw new IllegalStateException(
          "BOOTSTRAP_REGISTRY_PROTOCOL: " + contribution.contributionKey());
    }
    if (!required) {
      return;
    }
    // If this acknowledgement fails, no business action has started. Do not invent a failure state.
    registry.markRunning(
        scope,
        contribution.moduleCode(),
        contribution.contributionType(),
        contribution.contributionKey(),
        contribution.version(),
        contribution.checksum());
    try {
      contribution.action().run();
    } catch (Exception actionFailure) {
      try {
        registry.markFailed(
            scope,
            contribution.moduleCode(),
            contribution.contributionType(),
            contribution.contributionKey(),
            contribution.version(),
            contribution.checksum(),
            actionFailure.getClass().getSimpleName());
      } catch (Exception registryFailure) {
        actionFailure.addSuppressed(registryFailure);
      }
      throw actionFailure;
    }
    // An unavailable completion acknowledgement does not mean the business action failed.
    registry.markApplied(
        scope,
        contribution.moduleCode(),
        contribution.contributionType(),
        contribution.contributionKey(),
        contribution.version(),
        contribution.checksum());
  }

  private static void text(String value, int limit, String name) {
    if (value == null || value.isBlank() || value.length() > limit) {
      throw new IllegalArgumentException("Invalid bootstrap contribution " + name);
    }
  }
}
