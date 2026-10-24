package org.simplepoint.composition.fixture.first;

import org.simplepoint.composition.fixture.api.Greeting;
import org.simplepoint.platform.composition.PlatformModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/** Fixture used to verify explicit module selection and startup validation. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "simplepoint.fixture.first.enabled", havingValue = "true")
@PlatformModule(id = "first", packages = "org.simplepoint.composition.fixture.first",
    provides = Greeting.class)
public class FirstModule {
  @Bean
  @Lazy
  Greeting greetingProvider() {
    return name -> "Hello " + name;
  }
}
