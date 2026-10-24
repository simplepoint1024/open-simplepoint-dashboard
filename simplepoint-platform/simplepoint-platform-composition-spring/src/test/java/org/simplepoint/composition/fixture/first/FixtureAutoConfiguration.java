package org.simplepoint.composition.fixture.first;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/** An auto-configuration in a domain package must still require Boot import selection. */
@AutoConfiguration
public class FixtureAutoConfiguration {
  @Bean
  String fixtureInfrastructure() {
    return "auto-configuration";
  }
}
