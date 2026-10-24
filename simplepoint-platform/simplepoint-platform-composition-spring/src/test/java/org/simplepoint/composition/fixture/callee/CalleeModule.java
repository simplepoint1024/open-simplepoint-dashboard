package org.simplepoint.composition.fixture.callee;

import org.simplepoint.composition.fixture.api.CallerChain;
import org.simplepoint.composition.fixture.api.CallerIdentity;
import org.simplepoint.core.security.ServiceClientIdentity;
import org.simplepoint.platform.composition.PlatformModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;

/** Leaf provider and conditional undeclared-consumer fixture. */
@Configuration(proxyBeanMethods = false)
@PlatformModule(
    id = "callee",
    packages = "org.simplepoint.composition.fixture.callee",
    provides = CallerIdentity.class)
public class CalleeModule {
  @Bean
  CallerIdentity identityProvider() {
    return () ->
        ServiceClientIdentity.authenticatedClientId(
                SecurityContextHolder.getContext().getAuthentication())
            .orElseThrow();
  }

  @Bean
  @ConditionalOnProperty(name = "simplepoint.fixture.undeclared-consumer")
  Object forbiddenConsumer(CallerChain chain) {
    throw new AssertionError("Undeclared consumption must be rejected before creating the bean");
  }
}
