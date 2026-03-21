package org.simplepoint.security.oauth2.resourceserver.config;

import com.nimbusds.oauth2.sdk.GeneralException;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata;
import java.io.IOException;
import java.util.Collections;
import org.simplepoint.cache.CacheService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationGrantedAuthorityLoader;
import org.simplepoint.security.context.AuthorizationContextResolver;
import org.simplepoint.security.context.AuthorizationContextService;
import org.simplepoint.security.oauth2.resourceserver.AuthorizationContextFilter;
import org.simplepoint.security.oauth2.resourceserver.delegate.JwtAuthenticationConverterDelegate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Auto-configuration for the resource server.
 * 资源服务器的自动配置类
 */
@AutoConfiguration
public class ResourceServerAutoConfiguration {

  /**
   * Configures the security filter chain for the application, enforcing authentication
   * for all requests and enabling OAuth2 resource server with JWT authentication.
   *
   * <p>This bean defines a security filter that ensures all incoming requests are authenticated
   * and utilizes OAuth2 JWT tokens for identity verification.</p>
   *
   * @param http the {@link HttpSecurity} instance used for security configurations
   * @return a fully configured {@link SecurityFilterChain} instance
   * @throws Exception if an error occurs during the security configuration process
   */
  @Bean
  public SecurityFilterChain securityWebFilterChain(
      HttpSecurity http,
      AuthorizationGrantedAuthorityLoader authorizationGrantedAuthorityLoader,
      AuthorizationContextResolver authorizationContextResolver
  ) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable);
    http.addFilterBefore(new AuthorizationContextFilter(authorizationContextResolver), BearerTokenAuthenticationFilter.class);
    return http
        .authorizeHttpRequests(request -> request
            .requestMatchers("/actuator/**", "/static/**", "/v3/api-docs/**", "/swagger-ui/**", "/error", "/css/**", "/js/**", "/images/**")
            .permitAll()
            .anyRequest().authenticated()
        )
        .oauth2ResourceServer(configurer ->
            configurer.jwt(
                jwtConfigurer -> jwtConfigurer.jwtAuthenticationConverter(new JwtAuthenticationConverterDelegate(authorizationGrantedAuthorityLoader))
            )
        )
        .build();
  }

  /**
   * Defines a bean for the {@link AuthorizationGrantedAuthorityLoader}, which is responsible for
   * loading granted authorities based on JWT claims.
   *
   * <p>This implementation currently returns an empty list, but it can be customized to extract
   * authorities from JWT claims or other sources as needed.</p>
   *
   * @return a new instance of {@link AuthorizationGrantedAuthorityLoader}
   */
  @Bean
  public AuthorizationGrantedAuthorityLoader authorizationGrantedAuthorityLoader() {
    return claims -> {
      AuthorizationContext authorizationContext = AuthorizationContextHolder.getContext();
      if (authorizationContext == null) {
        return Collections.emptyList();
      }
      return authorizationContext.asAuthorities();
    };
  }


  /**
   * Creates a bean for AuthorizationContextResolver that uses Redis for caching authorization contexts.
   *
   * @param cacheService                the CacheService implementation for interacting with Redis to store and retrieve authorization contexts
   * @param authorizationContextService the AuthorizationContextService for calculating authorization contexts when not found in cache
   * @return an instance of AuthorizationContextResolver configured to use Redis for caching
   */
  @Bean
  public AuthorizationContextResolver authorizationContextResolver(
      CacheService cacheService,
      AuthorizationContextService authorizationContextService,
      OAuth2ResourceServerProperties resourceServerProperties
  ) throws GeneralException, IOException {
    return new AuthorizationContextResolver(
        "simplepoint:security:authorization-context:",
        cacheService,
        authorizationContextService,
        // 从 OIDC Provider Metadata 中获取 issuer URI，确保与 JWT 认证配置一致
        OIDCProviderMetadata.resolve(
            Issuer.parse(resourceServerProperties.getJwt().getIssuerUri())).getUserInfoEndpointURI()
    );
  }
}
