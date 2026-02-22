package org.simplepoint.security.oauth2.resourceserver.config;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationGrantedAuthorityLoader;
import org.simplepoint.security.context.AuthorizationContextResolver;
import org.simplepoint.security.oauth2.resourceserver.AuthorizationContextFilter;
import org.simplepoint.security.oauth2.resourceserver.delegate.JwtAuthenticationConverterDelegate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
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
      AuthorizationContextResolver authorizationContextResolver,
      HttpServletRequest servletRequest
  ) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable);
    http.addFilterBefore(new AuthorizationContextFilter(authorizationContextResolver, servletRequest), BearerTokenAuthenticationFilter.class);
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
  public AuthorizationGrantedAuthorityLoader authorizationGrantedAuthorityLoader(
      AuthorizationContextHolder contextHolder
  ) {
    return claims -> {
      AuthorizationContext authorizationContext = contextHolder.getAuthorizationContext();
      if (authorizationContext == null) {
        return Collections.emptyList();
      }
      return authorizationContext.asAuthorities();
    };
  }
}
