package org.simplepoint.composition.fixture.second;

import org.simplepoint.composition.fixture.api.Greeting;
import org.simplepoint.platform.composition.ModuleDependency;
import org.simplepoint.platform.composition.PlatformModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Fixture used to verify explicit module selection and startup validation. */
@Configuration(proxyBeanMethods = false)
@PlatformModule(id = "second", packages = "org.simplepoint.composition.fixture.second",
    requires = @ModuleDependency(id = "first"), consumes = Greeting.class)
public class SecondModule {
  @Bean
  Consumer consumer(final Greeting greeting) {
    return new Consumer(greeting);
  }

  /** Captures the dependency injected by the assembly. */
  public record Consumer(Greeting greeting) {}
}
