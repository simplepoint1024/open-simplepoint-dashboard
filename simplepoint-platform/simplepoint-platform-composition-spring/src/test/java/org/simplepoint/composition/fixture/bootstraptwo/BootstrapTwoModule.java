package org.simplepoint.composition.fixture.bootstraptwo;

import org.simplepoint.composition.fixture.api.BootstrapEffects;
import org.simplepoint.platform.bootstrap.BootstrapContribution;
import org.simplepoint.platform.bootstrap.PlatformBootstrapContribution;
import org.simplepoint.platform.bootstrap.PlatformBootstrapModuleInitializer;
import org.simplepoint.platform.composition.PlatformModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Bootstrap fixture with labels deliberately shared across module owners. */
@Configuration(proxyBeanMethods = false)
@PlatformModule(
    id = "bootstrap-two",
    packages = "org.simplepoint.composition.fixture.bootstraptwo",
    initializers = PlatformBootstrapModuleInitializer.class)
public class BootstrapTwoModule {
  /** Supplies a module action whose mutable label cannot select another module identity. */
  @Bean
  PlatformBootstrapContribution twoContribution(BootstrapEffects effects) {
    return () ->
        BootstrapContribution.versioned(
            "shared-label", "data", "same-key", "1", 20, () -> effects.add("two"));
  }

  /** Optional startup declarations can remain absent without becoming an implicit failure. */
  @Bean
  PlatformBootstrapContribution twoOptionalContribution() {
    return () -> null;
  }

  /** Produces an invalid duplicate only for the negative preparation scenario. */
  @Bean
  @ConditionalOnProperty(name = "fixture.duplicate", havingValue = "true")
  PlatformBootstrapContribution twoDuplicateContribution() {
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
