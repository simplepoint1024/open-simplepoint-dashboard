package org.simplepoint.composition.fixture.unused;

import org.simplepoint.platform.composition.PlatformModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Fixture used to verify explicit module selection and startup validation. */
@Configuration(proxyBeanMethods = false)
@PlatformModule(id = "unused", packages = "org.simplepoint.composition.fixture.unused")
public class UnusedModule {
  @Bean
  String mustNotStart() {
    throw new IllegalStateException("An unselected module was activated");
  }
}
