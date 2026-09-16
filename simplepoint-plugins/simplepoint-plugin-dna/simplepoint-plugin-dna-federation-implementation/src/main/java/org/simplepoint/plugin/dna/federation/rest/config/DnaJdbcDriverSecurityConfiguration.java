package org.simplepoint.plugin.dna.federation.rest.config;

import org.simplepoint.plugin.dna.federation.api.constants.DnaFederationPaths;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security overrides for externally accessible DNA JDBC driver endpoints.
 */
@Configuration
public class DnaJdbcDriverSecurityConfiguration {

  /**
   * Allows the JDBC driver gateway to perform its own credential validation.
   *
   * @param http http security builder
   * @return configured filter chain
   * @throws Exception when configuration fails
   */
  @Bean
  @Order(0)
  public SecurityFilterChain dnaJdbcDriverSecurityFilterChain(final HttpSecurity http) throws Exception {
    return http
        .securityMatcher(DnaFederationPaths.JDBC_DRIVER + "/**", DnaFederationPaths.PLATFORM_JDBC_DRIVER + "/**")
        .csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(request -> request.anyRequest().permitAll())
        .build();
  }
}
