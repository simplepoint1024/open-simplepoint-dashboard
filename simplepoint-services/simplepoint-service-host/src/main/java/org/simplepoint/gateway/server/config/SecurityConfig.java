/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.gateway.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.oidc.web.server.logout.OidcClientInitiatedServerLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * SecurityConfig class configures the Spring Security web filter chain
 * to support OAuth2 login and logout functionality.
 */
@Configuration
public class SecurityConfig {

  /**
   * Configures Spring Security's web filter chain, defining authorization rules,
   * OAuth2 login settings, and OIDC logout handling.
   *
   * @param http                         Instance of ServerHttpSecurity to build security settings
   * @param clientRegistrationRepository Repository for OAuth2 client registration
   * @return SecurityWebFilterChain instance
   */
  @Bean
  public SecurityWebFilterChain securityWebFilterChain(
      final ServerHttpSecurity http,
      final ReactiveClientRegistrationRepository clientRegistrationRepository,
      @Value("${spring.security.oauth2.client.login-page}") final String loginPage
  ) {
    http.csrf(ServerHttpSecurity.CsrfSpec::disable);
    http.authorizeExchange(exchanges -> exchanges
            // Allow access to login page and OAuth2-related paths
            .pathMatchers(
                "/{registrationId}/authorize",
                "/{registrationId}/login",
                "/login",
                "/authorization/**",
                "/actuator/**",
                "/static/**"
            ).permitAll()
            // Require authentication for all other requests
            .anyExchange().authenticated()
        )

        // Configure OAuth2 login with client registration repository and custom login page
        .oauth2Login(oauth2 -> oauth2.clientRegistrationRepository(clientRegistrationRepository)
            .loginPage("/login"))

        // Configure logout handling using OIDC client-initiated logout success handler
        .logout(logout -> logout
            .logoutSuccessHandler(new OidcClientInitiatedServerLogoutSuccessHandler(clientRegistrationRepository))
            .logoutUrl("/logout")
        );

    return http.build();
  }
}
