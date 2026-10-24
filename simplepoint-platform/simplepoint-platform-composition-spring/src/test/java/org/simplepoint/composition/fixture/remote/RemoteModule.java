package org.simplepoint.composition.fixture.remote;

import org.simplepoint.composition.fixture.api.Greeting;
import org.simplepoint.platform.composition.PlatformModule;
import org.springframework.context.annotation.Configuration;

/** Fixture used to verify explicit module selection and startup validation. */
@Configuration(proxyBeanMethods = false)
@PlatformModule(
    id = "remote",
    packages = "org.simplepoint.composition.fixture.remote",
    consumes = Greeting.class)
public class RemoteModule {
  @org.springframework.context.annotation.Bean
  Consumer remoteConsumer(Greeting greeting) {
    return new Consumer(greeting);
  }

  /** Captures the requesting module without needing a local provider. */
  public record Consumer(Greeting greeting) {}
}
