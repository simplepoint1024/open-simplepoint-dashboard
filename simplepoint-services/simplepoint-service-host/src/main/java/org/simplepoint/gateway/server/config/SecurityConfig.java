/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.gateway.server.config;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.oidc.web.server.logout.OidcClientInitiatedServerLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoderFactory;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

/**
 * SecurityConfig class configures the Spring Security web filter chain
 * to support OAuth2 login and logout functionality.
 */
@Configuration
public class SecurityConfig {

  private static final String END_SESSION_ENDPOINT = "end_session_endpoint";

  private static final String DEFAULT_OIDC_LOGOUT_PATH = "/connect/logout";

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
      final ReactiveClientRegistrationRepository clientRegistrationRepository
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
                "/static/**",
                "/{service}/mf/**",
                "/ai/v1/**",
                "/svg.svg",
                "/.well-known/appspecific/**"
            ).permitAll()
            // Require authentication for all other requests
            .anyExchange().authenticated()
        )

        // Configure OAuth2 login with client registration repository and custom login page
        .oauth2Login(oauth2 -> oauth2
            .clientRegistrationRepository(clientRegistrationRepository)
            //.authenticationSuccessHandler(new SessionServerAuthenticationSuccessHandler())

            .loginPage("/login"))

        // Configure logout handling using OIDC client-initiated logout success handler
        .logout(logout -> logout
            .logoutSuccessHandler(oidcLogoutSuccessHandler(clientRegistrationRepository))
            .logoutUrl("/logout")
        );

    return http.build();
  }

  private OidcClientInitiatedServerLogoutSuccessHandler oidcLogoutSuccessHandler(
      final ReactiveClientRegistrationRepository clientRegistrationRepository
  ) {
    OidcClientInitiatedServerLogoutSuccessHandler handler =
        new OidcClientInitiatedServerLogoutSuccessHandler(clientRegistrationRepository);
    handler.setPostLogoutRedirectUri("{baseUrl}");
    handler.setRedirectUriResolver(parameters -> {
      URI endSessionEndpoint = endSessionEndpoint(parameters.getClientRegistration());
      if (endSessionEndpoint == null) {
        return Mono.empty();
      }

      Authentication authentication = parameters.getAuthentication();
      OidcUser oidcUser = (OidcUser) authentication.getPrincipal();
      String postLogoutRedirectUri = postLogoutRedirectUri(
          parameters.getServerWebExchange().getRequest()
      );
      return Mono.just(endpointUri(
          endSessionEndpoint,
          oidcUser.getIdToken().getTokenValue(),
          postLogoutRedirectUri
      ));
    });
    return handler;
  }

  private URI endSessionEndpoint(final ClientRegistration clientRegistration) {
    Object configuredEndpoint = clientRegistration.getProviderDetails()
        .getConfigurationMetadata()
        .get(END_SESSION_ENDPOINT);
    if (configuredEndpoint != null) {
      return URI.create(configuredEndpoint.toString());
    }

    String authorizationUri = clientRegistration.getProviderDetails().getAuthorizationUri();
    if (!StringUtils.hasText(authorizationUri)) {
      return null;
    }
    return UriComponentsBuilder.fromUriString(authorizationUri)
        .replacePath(DEFAULT_OIDC_LOGOUT_PATH)
        .replaceQuery(null)
        .fragment(null)
        .build()
        .toUri();
  }

  private String postLogoutRedirectUri(final ServerHttpRequest request) {
    UriComponents uriComponents = UriComponentsBuilder.fromUri(request.getURI())
        .replacePath(request.getPath().contextPath().value())
        .replaceQuery(null)
        .fragment(null)
        .build();
    return uriComponents.toUriString();
  }

  private String endpointUri(
      final URI endSessionEndpoint,
      final String idToken,
      final String postLogoutRedirectUri
  ) {
    return UriComponentsBuilder.fromUri(endSessionEndpoint)
        .queryParam("id_token_hint", idToken)
        .queryParam("post_logout_redirect_uri", postLogoutRedirectUri)
        .encode(StandardCharsets.UTF_8)
        .build()
        .toUriString();
  }

  /**
   * Configures a ReactiveJwtDecoderFactory to create JWT decoders
   * that support the PS256 signature algorithm for OIDC clients.
   *
   * @return ReactiveJwtDecoderFactory instance
   */
  @Bean
  public ReactiveJwtDecoderFactory<ClientRegistration> oidcDecoderFactory() {

    return clientRegistration -> NimbusReactiveJwtDecoder
        .withJwkSetUri(clientRegistration.getProviderDetails().getJwkSetUri())
        .jwsAlgorithms(algorithms -> algorithms.add(SignatureAlgorithm.PS256))
        .build();
  }

}
