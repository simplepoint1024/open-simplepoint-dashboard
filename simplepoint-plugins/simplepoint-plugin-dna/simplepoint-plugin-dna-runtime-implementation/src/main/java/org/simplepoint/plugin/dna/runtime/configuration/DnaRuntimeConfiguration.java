package org.simplepoint.plugin.dna.runtime.configuration;

import org.simplepoint.data.calcite.core.query.CalciteQueryEngine;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;
import org.simplepoint.events.api.ModuleEvents;
import org.simplepoint.platform.composition.ModuleDependency;
import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.plugin.dna.runtime.api.service.DnaFormRuntimeService;
import org.simplepoint.plugin.dna.runtime.api.service.DnaOwnedRecordService;
import org.simplepoint.plugin.dna.runtime.api.service.PhysicalBindingTargetResolver;
import org.simplepoint.plugin.dna.runtime.api.service.PhysicalTableNamingStrategy;
import org.simplepoint.plugin.dna.runtime.service.support.ConfiguredPhysicalBindingTargetResolver;
import org.simplepoint.plugin.dna.runtime.service.support.DefaultPhysicalTableNamingStrategy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Replaceable defaults for physical placement and immutable table naming. */
@ModuleEvents(RuntimeEventDeclarations.class)
@ModuleMigrations
@PlatformModule(
    id = "dna-runtime",
    packages = {"org.simplepoint.plugin.dna.runtime", "org.simplepoint.dna.runtime.service"},
    requires = {@ModuleDependency(id = "dna-core"), @ModuleDependency(id = "dna-federation")},
    provides = {DnaFormRuntimeService.class, DnaOwnedRecordService.class})
@Configuration(proxyBeanMethods = false)
@org.springframework.scheduling.annotation.EnableScheduling
@EnableConfigurationProperties({
  DnaRuntimePlacementProperties.class,
  DnaRuntimeExecutionProperties.class,
  DnaRuntimePublicationProperties.class,
  DnaFormRuntimeRemoteProperties.class
})
public class DnaRuntimeConfiguration {

  @Bean
  @ConditionalOnMissingBean
  PhysicalTableNamingStrategy physicalTableNamingStrategy() {
    return new DefaultPhysicalTableNamingStrategy();
  }

  @Bean
  @ConditionalOnMissingBean
  PhysicalBindingTargetResolver physicalBindingTargetResolver(
      final DnaRuntimePlacementProperties properties) {
    return new ConfiguredPhysicalBindingTargetResolver(properties);
  }

  @Bean
  @ConditionalOnMissingBean(CalciteQueryEngine.class)
  CalciteQueryEngine calciteQueryEngine() {
    return new DefaultCalciteQueryEngine();
  }
}
