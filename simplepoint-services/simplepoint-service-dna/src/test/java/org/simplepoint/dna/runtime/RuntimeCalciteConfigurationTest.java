package org.simplepoint.dna.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.simplepoint.data.calcite.core.query.CalciteQueryEngine;
import org.simplepoint.plugin.dna.federation.service.config.FederationCalciteConfiguration;
import org.simplepoint.plugin.dna.runtime.configuration.DnaRuntimeConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class RuntimeCalciteConfigurationTest {

  @Test
  void federationAndRuntimeShareExactlyOneCalciteEngine() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.register(FederationCalciteConfiguration.class, DnaRuntimeConfiguration.class);
      context.refresh();

      assertThat(context.getBeansOfType(CalciteQueryEngine.class)).hasSize(1);
    }
  }
}
