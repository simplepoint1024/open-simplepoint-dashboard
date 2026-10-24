package org.simplepoint.composition.fixture.callertwo;

import java.util.List;
import org.simplepoint.composition.fixture.api.CallerChain;
import org.simplepoint.composition.fixture.api.CallerIdentity;
import org.simplepoint.core.security.ServiceClientIdentity;
import org.simplepoint.platform.composition.PlatformModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;

/** Provider and consumer fixture for nested module transitions. */
@Configuration(proxyBeanMethods = false)
@PlatformModule(
    id = "caller-two",
    packages = "org.simplepoint.composition.fixture.callertwo",
    consumes = CallerIdentity.class,
    provides = CallerChain.class)
public class CallerTwoModule {
  @Bean
  CallerChain chainProvider(CallerIdentity identity) {
    return () -> List.of(caller(), identity.caller(), caller());
  }

  /** Builds a second independent consumer of the same contract. */
  @Bean
  public Consumer otherConsumer(CallerIdentity identity) {
    return new Consumer(identity);
  }

  /** Keeps the module-specific client injected into a factory method. */
  public record Consumer(CallerIdentity identity) {}

  private static String caller() {
    return ServiceClientIdentity.authenticatedClientId(
            SecurityContextHolder.getContext().getAuthentication())
        .orElseThrow();
  }
}
