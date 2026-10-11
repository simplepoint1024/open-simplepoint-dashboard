package org.simplepoint.plugin.dna.federation.service.config;

import org.simplepoint.data.calcite.core.query.CalciteQueryEngine;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for federation Calcite services.
 */
@Configuration
public class FederationCalciteConfiguration {

  /**
   * Creates the shared Calcite query engine bean.
   *
   * @return Calcite query engine
   */
  @Bean
  @ConditionalOnMissingBean(CalciteQueryEngine.class)
  public CalciteQueryEngine calciteQueryEngine() {
    return new DefaultCalciteQueryEngine();
  }
}
