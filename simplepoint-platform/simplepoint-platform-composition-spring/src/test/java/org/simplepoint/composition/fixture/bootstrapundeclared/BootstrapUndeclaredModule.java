package org.simplepoint.composition.fixture.bootstrapundeclared;

import org.simplepoint.composition.fixture.api.BootstrapEffects;
import org.simplepoint.platform.bootstrap.BootstrapContribution;
import org.simplepoint.platform.bootstrap.PlatformBootstrapContribution;
import org.simplepoint.platform.composition.PlatformModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Bootstrap fixture with labels deliberately shared across module owners. */
@Configuration(proxyBeanMethods = false)
@PlatformModule(
    id = "bootstrap-undeclared",
    packages = "org.simplepoint.composition.fixture.bootstrapundeclared")
public class BootstrapUndeclaredModule {
  /** Supplies a module action whose mutable label cannot select another module identity. */
  @Bean
  PlatformBootstrapContribution undeclaredContribution(BootstrapEffects effects) {
    return () ->
        BootstrapContribution.versioned(
            "shared-label", "data", "same-key", "1", 20, () -> effects.add("undeclared"));
  }

  /** Optional startup declarations can remain absent without becoming an implicit failure. */
  @Bean
  PlatformBootstrapContribution undeclaredOptionalContribution() {
    return () -> null;
  }

  /** Produces an invalid duplicate only for the negative preparation scenario. */
  @Bean
  @ConditionalOnProperty(name = "fixture.duplicate", havingValue = "true")
  PlatformBootstrapContribution undeclaredDuplicateContribution() {
    return () ->
        BootstrapContribution.versioned(
            "shared-label",
            "data",
            "same-key",
            "2",
            30,
            () -> {
              throw new AssertionError("Duplicate work must never execute");
            });
  }
}
