package org.simplepoint.plugin.ai.core.rest.config;

import org.simplepoint.plugin.ai.core.api.constants.AiPaths;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/** Isolates the public compatibility gateway from OAuth while controllers enforce API keys. */
@Configuration
public class AiGatewaySecurityConfiguration {

  /** Allows public gateway requests through so controller-level API key auth can run. */
  @Bean
  @Order(0)
  public SecurityFilterChain aiGatewaySecurityFilterChain(
      final HttpSecurity http,
      org.springframework.beans.factory.ObjectProvider<
              org.simplepoint.platform.composition.ModulePlan>
          modules)
      throws Exception {
    String prefix =
        org.simplepoint.platform.http.servlet.ModuleHttpPaths.prefix(
            modules.getIfAvailable(), AiGatewaySecurityConfiguration.class);
    return http.securityMatcher(prefix + AiPaths.COMPATIBLE_API + "/**")
        .csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
        .build();
  }
}
