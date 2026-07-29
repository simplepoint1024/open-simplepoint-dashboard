package org.simplepoint.mcp.gateway.security;

import jakarta.servlet.http.HttpServletResponse;
import org.simplepoint.mcp.gateway.config.McpGatewayProperties;
import org.simplepoint.mcp.gateway.publication.McpPublicationRateLimiter;
import org.simplepoint.mcp.gateway.publication.McpPublicationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * Security boundary for the first internal MCP Gateway slice.
 */
@Configuration(proxyBeanMethods = false)
public class McpGatewaySecurityConfiguration {

  /**
   * Routes path-specific RFC 9728 metadata to the publication-aware MVC controller.
   *
   * <p>Spring Security's resource-server configurer installs a generic RFC 9728
   * filter. This higher-priority chain intentionally excludes publication metadata
   * from that filter so metadata is resolved from the immutable publication
   * manifest rather than synthesized from request headers.</p>
   *
   * @param http HTTP security
   * @return metadata filter chain
   * @throws Exception on configuration errors
   */
  @Bean
  @Order(1)
  public SecurityFilterChain mcpProtectedResourceMetadataSecurityFilterChain(
      final HttpSecurity http
  ) throws Exception {
    return http
        .securityMatcher("/.well-known/oauth-protected-resource/mcp/**")
        .csrf(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .sessionManagement(session ->
            session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
        .build();
  }

  /**
   * Secures all other Gateway endpoints and permits health probes without authentication.
   *
   * @param http HTTP security
   * @param properties gateway properties
   * @return filter chain
   * @throws Exception on configuration errors
   */
  @Bean
  @Order(2)
  public SecurityFilterChain mcpGatewaySecurityFilterChain(
      final HttpSecurity http,
      final McpGatewayProperties properties,
      final McpPublicationRegistry publicationRegistry,
      final McpPublicationRateLimiter rateLimiter,
      final JwtDecoder jwtDecoder
  ) throws Exception {
    InternalTokenAuthenticationFilter internalFilter =
        new InternalTokenAuthenticationFilter(properties);
    McpPublicationAuthorizationFilter publicationFilter =
        new McpPublicationAuthorizationFilter(publicationRegistry, rateLimiter);
    return http
        .csrf(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .sessionManagement(session ->
            session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(requests -> requests
            .requestMatchers(
                "/actuator/health",
                "/actuator/health/**",
                "/error",
                McpOauthClientMetadataDocument.PATH,
                "/.well-known/oauth-protected-resource/**"
            ).permitAll()
            .requestMatchers("/internal/mcp/**").hasRole("MCP_GATEWAY_SERVICE")
            .requestMatchers("/mcp/**").authenticated()
            .anyRequest().denyAll())
        .addFilterBefore(internalFilter, AnonymousAuthenticationFilter.class)
        .addFilterAfter(publicationFilter, BearerTokenAuthenticationFilter.class)
        .oauth2ResourceServer(oauth -> oauth
            .jwt(jwt -> jwt.decoder(jwtDecoder))
            .authenticationEntryPoint((request, response, exception) -> {
              String code = McpPublicationAuthorizationFilter.publicationCode(request);
              if (code != null) {
                try {
                  McpPublicationAuthorizationFilter.unauthorized(
                      response,
                      publicationRegistry.manifest(code).canonicalResourceUri(),
                      "invalid_token"
                  );
                } catch (RuntimeException ex) {
                  response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                }
              } else {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
              }
            }))
        .build();
  }

  /**
   * Validates issuer, expiry, signature and PS256 before publication-specific checks.
   */
  @Bean
  public JwtDecoder mcpPublicationJwtDecoder(
      final McpGatewayProperties properties
  ) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder
        .withJwkSetUri(properties.getPublicationJwkSetUri())
        .jwsAlgorithm(SignatureAlgorithm.PS256)
        .build();
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(properties.getPublicationIssuerUri())
    ));
    return decoder;
  }
}
