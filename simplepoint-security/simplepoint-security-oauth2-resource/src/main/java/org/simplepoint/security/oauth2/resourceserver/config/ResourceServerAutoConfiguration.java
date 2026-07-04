package org.simplepoint.security.oauth2.resourceserver.config;

import com.nimbusds.oauth2.sdk.GeneralException;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import org.simplepoint.cache.CacheService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationGrantedAuthorityLoader;
import org.simplepoint.security.context.AuthorizationContextResolver;
import org.simplepoint.security.context.AuthorizationContextService;
import org.simplepoint.security.oauth2.resourceserver.AuthorizationContextFilter;
import org.simplepoint.security.oauth2.resourceserver.delegate.JwtAuthenticationConverterDelegate;
import org.simplepoint.security.token.TokenRevocationService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.StringUtils;

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
   * @return a fully configured {@link SecurityFilterChain} instance
   * @throws Exception if an error occurs during the security configuration process
   */
  @Bean
  @ConditionalOnMissingBean
  public TokenRevocationService tokenRevocationService(final CacheService cacheService) {
    return new TokenRevocationService(cacheService);
  }

  /**
   * @ Bean.
   */
  @Bean
  public SecurityFilterChain securityWebFilterChain(
      HttpSecurity http,
      AuthorizationGrantedAuthorityLoader authorizationGrantedAuthorityLoader,
      AuthorizationContextResolver authorizationContextResolver,
      TokenRevocationService tokenRevocationService,
      Environment environment
  ) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable);
    http.addFilterBefore(new AuthorizationContextFilter(authorizationContextResolver), BearerTokenAuthenticationFilter.class);
    String serviceRouterToken = environment.getProperty("simplepoint.service-router.internal-auth.token");
    String serviceRouterHeaderName = environment.getProperty(
        "simplepoint.service-router.internal-auth.header-name",
        "X-SimplePoint-Service-Router-Token"
    );
    String serviceRouterExposePath = environment.getProperty(
        "simplepoint.service-router.provider.expose-path",
        "/_simplepoint/service-router/invoke"
    );
    return http
        .authorizeHttpRequests(request -> {
          request.requestMatchers(
                  "/actuator/**", "/static/**", "/mf/**", "/v3/api-docs/**", "/swagger-ui/**",
                  "/error", "/css/**", "/js/**", "/images/**"
              )
              .permitAll();
          if (StringUtils.hasText(serviceRouterToken)) {
            request.requestMatchers(new ServiceRouterInternalRequestMatcher(
                    serviceRouterExposePath,
                    serviceRouterHeaderName,
                    serviceRouterToken
                ))
                .permitAll();
          }
          request.anyRequest().authenticated();
        })
        .oauth2ResourceServer(configurer ->
            configurer.jwt(
                jwtConfigurer -> jwtConfigurer.jwtAuthenticationConverter(
                    new JwtAuthenticationConverterDelegate(
                        authorizationGrantedAuthorityLoader,
                        tokenRevocationService,
                        environment.getProperty("simplepoint.security.oauth2.token.audience", "simplepoint-api")
                    )
                )
            )
        )
        .build();
  }

  record ServiceRouterInternalRequestMatcher(
      String exposePath,
      String headerName,
      String token
  ) implements RequestMatcher {

    @Override
    public boolean matches(final HttpServletRequest request) {
      if (!HttpMethod.POST.matches(request.getMethod())) {
        return false;
      }
      if (!matchesPath(request)) {
        return false;
      }
      String providedToken = request.getHeader(headerName);
      return StringUtils.hasText(providedToken) && constantTimeEquals(providedToken, token);
    }

    private boolean matchesPath(final HttpServletRequest request) {
      String servletPath = request.getServletPath();
      String requestUri = request.getRequestURI();
      String contextPath = request.getContextPath();
      if (exposePath.equals(servletPath)) {
        return true;
      }
      if (exposePath.equals(requestUri)) {
        return true;
      }
      return StringUtils.hasText(contextPath) && requestUri.startsWith(contextPath)
          && exposePath.equals(requestUri.substring(contextPath.length()));
    }

    private boolean constantTimeEquals(final String left, final String right) {
      return MessageDigest.isEqual(
          left.getBytes(StandardCharsets.UTF_8),
          right.getBytes(StandardCharsets.UTF_8)
      );
    }
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
      OAuth2ResourceServerProperties resourceServerProperties,
      Environment environment
  ) throws GeneralException, IOException {
    String userInfoUri = environment.getProperty("simplepoint.security.oauth2.user-info-uri");
    URI userInfoEndpoint = StringUtils.hasText(userInfoUri)
        ? URI.create(userInfoUri)
        : OIDCProviderMetadata.resolve(
            Issuer.parse(resourceServerProperties.getJwt().getIssuerUri())).getUserInfoEndpointURI();
    return new AuthorizationContextResolver(
        "simplepoint:security:authorization-context:",
        cacheService,
        authorizationContextService,
        userInfoEndpoint
    );
  }
}
