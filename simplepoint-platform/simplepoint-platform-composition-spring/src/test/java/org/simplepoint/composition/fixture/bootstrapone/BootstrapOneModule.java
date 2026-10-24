package org.simplepoint.composition.fixture.bootstrapone;

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
    id = "bootstrap-one",
    packages = "org.simplepoint.composition.fixture.bootstrapone",
    initializers = {
      BootstrapOneModule.RegistrationProbe.class,
      PlatformBootstrapModuleInitializer.class
    })
public class BootstrapOneModule {
  /** Supplies a module action whose mutable label cannot select another module identity. */
  @Bean
  PlatformBootstrapContribution oneContribution(BootstrapEffects effects) {
    return () ->
        BootstrapContribution.versioned(
            "shared-label", "data", "same-key", "1", 30, () -> effects.add("one"));
  }

  /** Optional startup declarations can remain absent without becoming an implicit failure. */
  @Bean
  PlatformBootstrapContribution oneOptionalContribution() {
    return () -> null;
  }

  /** Produces an invalid duplicate only for the negative preparation scenario. */
  @Bean
  @ConditionalOnProperty(name = "fixture.duplicate", havingValue = "true")
  PlatformBootstrapContribution oneDuplicateContribution() {
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

  /** Existing bootstrap priorities can interleave different modules. */
  @Bean
  @ConditionalOnProperty(name = "fixture.ordered", havingValue = "true")
  PlatformBootstrapContribution oneEarlyContribution(BootstrapEffects effects) {
    return () ->
        BootstrapContribution.versioned(
            "shared-label", "data", "early-key", "1", 10, () -> effects.add("one-early"));
  }

  /** Deliberately declared before bootstrap to verify that the framework stage takes precedence. */
  @org.simplepoint.platform.composition.ModuleInitialization
  public static final class RegistrationProbe
      implements org.simplepoint.platform.composition.ModuleInitializer {
    private final BootstrapEffects effects;
    private final org.springframework.core.env.Environment environment;

    /** Captures local observation infrastructure without a module RPC dependency. */
    public RegistrationProbe(
        BootstrapEffects effects, org.springframework.core.env.Environment environment) {
      this.effects = effects;
      this.environment = environment;
    }

    @Override
    public Runnable prepare(
        org.simplepoint.platform.composition.ModuleInitializationContext context) {
      return () -> {
        if (environment.getProperty("fixture.ordered", Boolean.class, false)) {
          effects.add("one-registration");
        }
      };
    }
  }
}
