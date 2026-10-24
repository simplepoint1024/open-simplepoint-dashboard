package org.simplepoint.composition.fixture.undeclared;

import org.simplepoint.composition.fixture.api.Greeting;
import org.simplepoint.platform.composition.PlatformModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Fixture used to verify explicit module selection and startup validation. */
@Configuration(proxyBeanMethods = false)
@PlatformModule(id = "undeclared", packages = "org.simplepoint.composition.fixture.undeclared")
public class UndeclaredModule {
  @Bean
  Greeting unannouncedProvider() {
    return name -> name;
  }
}
