package org.simplepoint.data.jpa.composition;

import org.simplepoint.platform.composition.ModulePlan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Registers infrastructure before domain singletons are instantiated. */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement
@EnableJpaAuditing
public class ModulePersistenceConfiguration {
  @Bean
  static ModulePersistenceRegistrar modulePersistenceRegistrar(
      ModulePlan plan, Environment environment, ResourceLoader loader) {
    return new ModulePersistenceRegistrar(plan, environment, loader);
  }
}
