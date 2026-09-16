/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.cloud.oauth.server.configuration;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.cache.CacheService;
import org.simplepoint.cloud.oauth.server.expansion.oidc.OidcConfigurerExpansion;
import org.simplepoint.cloud.oauth.server.expansion.oidc.OidcUserInfoAuthenticationExpansion;
import org.simplepoint.cloud.oauth.server.oidc.DefaultOidcConfigurerExpansion;
import org.simplepoint.cloud.oauth.server.oidc.OpenidOidcUserInfoAuthentication;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.security.decorator.TokenDecorator;
import org.simplepoint.security.token.TokenRevocationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationGrantAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * Configuration class for OpenID Connect (OIDC) settings.
 * OpenID Connect (OIDC) 设置的配置类
 */
@Configuration
public class OidcConfiguration {

  /**
   * @ Bean.
   */
  @Bean
  @ConditionalOnMissingBean
  public TokenRevocationService tokenRevocationService(final CacheService cacheService) {
    return new TokenRevocationService(cacheService);
  }

  /**
   * Provides the OIDC configuration expansion bean.
   * 提供 OIDC 配置扩展的 Bean
   *
   * @param oidcUserInfoAuthenticationExpansion the OIDC user info authentication expansion
   *                                            OIDC 用户信息认证扩展
   * @return the OIDC configuration expansion OIDC 配置扩展
   */
  @Bean
  @ConditionalOnMissingBean(OidcConfigurerExpansion.class)
  public OidcConfigurerExpansion oidcConfigurer(
      final OidcUserInfoAuthenticationExpansion oidcUserInfoAuthenticationExpansion,
      final OAuth2AuthorizationService authorizationService,
      final TokenRevocationService tokenRevocationService,
      final RegisteredClientRepository registeredClientRepository
  ) {
    return new DefaultOidcConfigurerExpansion(
        oidcUserInfoAuthenticationExpansion,
        authorizationService,
        tokenRevocationService,
        registeredClientRepository
    );
  }

  /**
   * Configures the OIDC user info authentication expansion Bean.
   * If no available {@link OidcUserInfoAuthenticationExpansion} Bean exists, a default implementation is created.
   * 配置 OIDC 用户信息认证扩展的 Bean
   * 如果没有可用的 {@link OidcUserInfoAuthenticationExpansion} Bean，则创建默认实现
   */
  @Bean
  @ConditionalOnMissingBean(OidcUserInfoAuthenticationExpansion.class)
  public OidcUserInfoAuthenticationExpansion oidcUserInfoAuthentication(
      final UsersService usersService
  ) {
    /*
     * Uses {@link UsersService} as a dependency for user info authentication.
     * 使用 {@link UsersService} 作为用户信息认证服务的依赖项
     *
     * @param usersService The user service that provides user information retrieval.
     *                     用户服务，提供用户信息获取功能
     * @return The default implementation of OIDC user info authentication expansion.
     *         默认的 OIDC 用户信息认证扩展实现
     */
    return new OpenidOidcUserInfoAuthentication(usersService);
  }

  /**
   * Customizes the JWT token claims.
   * 定制 JWT 令牌声明
   *
   * @return the OAuth2 token customizer OAuth2 令牌定制器
   */
  @Bean
  public OAuth2TokenCustomizer<JwtEncodingContext> jwtCustomizer(
      final Set<TokenDecorator> tokenDecorator,
      final SessionRegistry sessionRegistry,
      @Value("${simplepoint.security.oauth2.token.audience:simplepoint-api}")
      final String tokenAudience,
      @Value("${simplepoint.security.oauth2.resource-indicator.allowed-prefixes:}")
      final String allowedResourcePrefixes
  ) {

    return context -> {
      context.getJwsHeader().algorithm(SignatureAlgorithm.PS256);
      if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())
          && StringUtils.hasText(tokenAudience)) {
        List<String> audiences = resolveAccessTokenAudiences(
            context,
            tokenAudience,
            allowedResourcePrefixes
        );
        context.getClaims().audience(audiences);
        context.getClaims().claims(claims -> claims.put("aud", audiences));
      }
      addSessionIdClaimIfAvailable(context, sessionRegistry);
      for (TokenDecorator decorator : tokenDecorator) {
        // 获取认证主体
        Authentication principal = context.getPrincipal();
        // 装饰令牌声明
        Map<String, Object> claims = decorator.resolveTokenClaims(principal, context.getTokenType().getValue());
        if (claims != null) {
          // 将装饰的声明添加到令牌上下文中
          claims.forEach((key, value) -> context.getClaims().claim(key, value));
        }
      }
    };
  }

  OAuth2TokenCustomizer<JwtEncodingContext> jwtCustomizer(
      final Set<TokenDecorator> tokenDecorator,
      final SessionRegistry sessionRegistry,
      final String tokenAudience
  ) {
    return jwtCustomizer(tokenDecorator, sessionRegistry, tokenAudience, "");
  }

  private static List<String> resolveAccessTokenAudiences(
      final JwtEncodingContext context,
      final String tokenAudience,
      final String allowedResourcePrefixes
  ) {
    LinkedHashSet<String> audiences = new LinkedHashSet<>();
    String resource = resolveResourceIndicator(context);
    if (StringUtils.hasText(resource)) {
      validateResourceIndicator(resource, allowedResourcePrefixes);
      audiences.add(resource);
    } else {
      audiences.add(tokenAudience);
    }
    RegisteredClient registeredClient = context.getRegisteredClient();
    if (registeredClient != null && StringUtils.hasText(registeredClient.getClientId())) {
      audiences.add(registeredClient.getClientId());
    }
    return new ArrayList<>(audiences);
  }

  private static String resolveResourceIndicator(final JwtEncodingContext context) {
    if (context.getAuthorization() == null) {
      return null;
    }
    OAuth2AuthorizationRequest authorizationRequest = context.getAuthorization()
        .getAttribute(OAuth2AuthorizationRequest.class.getName());
    String authorizedResource = authorizationRequest == null
        ? null : singleResource(
            authorizationRequest.getAdditionalParameters().get("resource")
        );
    if (!StringUtils.hasText(authorizedResource)) {
      return null;
    }
    Authentication grant = context.getAuthorizationGrant();
    if (grant instanceof OAuth2AuthorizationGrantAuthenticationToken grantToken) {
      String requestedResource = singleResource(
          grantToken.getAdditionalParameters().get("resource")
      );
      if (!authorizedResource.equals(requestedResource)) {
        throw oauthError(
            "invalid_target",
            "The token request resource must match the authorized resource"
        );
      }
    }
    return authorizedResource;
  }

  private static String singleResource(final Object value) {
    if (value instanceof String text && StringUtils.hasText(text)) {
      return text.trim();
    }
    if (value instanceof List<?> values && values.size() == 1
        && values.getFirst() instanceof String text && StringUtils.hasText(text)) {
      return text.trim();
    }
    if (value != null) {
      throw oauthError("invalid_target", "Exactly one resource indicator is required");
    }
    return null;
  }

  private static void validateResourceIndicator(
      final String resource,
      final String configuredPrefixes
  ) {
    URI uri;
    try {
      uri = URI.create(resource);
    } catch (IllegalArgumentException ex) {
      throw oauthError("invalid_target", "The resource indicator is not a valid URI");
    }
    boolean localhost = uri.getHost() != null
        && ("localhost".equalsIgnoreCase(uri.getHost())
        || uri.getHost().toLowerCase(java.util.Locale.ROOT).endsWith(".localhost"));
    List<String> prefixes = java.util.Arrays.stream(
            configuredPrefixes == null ? new String[0] : configuredPrefixes.split("[,\\s]+")
        )
        .filter(StringUtils::hasText)
        .map(String::trim)
        .toList();
    boolean explicitPrefix = prefixes.stream().anyMatch(resource::startsWith);
    if (uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || (!"https".equalsIgnoreCase(uri.getScheme())
        && !(httpResource(uri) && (localhost || explicitPrefix)))) {
      throw oauthError(
          "invalid_target",
          "The resource indicator must be an HTTPS URI"
      );
    }
    if (!prefixes.isEmpty() && !explicitPrefix) {
      throw oauthError("invalid_target", "The resource indicator is not allowed");
    }
  }

  private static boolean httpResource(final URI uri) {
    return "http".equalsIgnoreCase(uri.getScheme());
  }

  private static OAuth2AuthenticationException oauthError(
      final String code,
      final String description
  ) {
    return new OAuth2AuthenticationException(new OAuth2Error(code, description, null));
  }

  private static void addSessionIdClaimIfAvailable(
      final JwtEncodingContext context,
      final SessionRegistry sessionRegistry
  ) {
    if (!OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue())
        || context.getClaims().build().hasClaim("sid")) {
      return;
    }
    SessionInformation sessionInformation = findLatestSessionInformation(
        sessionRegistry,
        context.getPrincipal()
    );
    if (sessionInformation != null) {
      context.getClaims().claim("sid", createHash(sessionInformation.getSessionId()));
    }
  }

  private static SessionInformation findLatestSessionInformation(
      final SessionRegistry sessionRegistry,
      final Authentication authentication
  ) {
    if (authentication == null || !StringUtils.hasText(authentication.getName())) {
      return null;
    }

    List<SessionInformation> sessions = sessionRegistry.getAllSessions(
        authentication.getPrincipal(),
        false
    );
    if (CollectionUtils.isEmpty(sessions)) {
      sessions = sessionRegistry.getAllPrincipals().stream()
          .filter(principal -> principalMatches(authentication.getName(), principal))
          .flatMap(principal -> sessionRegistry.getAllSessions(principal, false).stream())
          .toList();
    }
    return sessions.stream()
        .max(Comparator.comparing(SessionInformation::getLastRequest))
        .orElse(null);
  }

  private static boolean principalMatches(final String expectedName, final Object principal) {
    if (principal instanceof Authentication authentication) {
      return expectedName.equals(authentication.getName());
    }
    if (principal instanceof UserDetails userDetails) {
      return expectedName.equals(userDetails.getUsername());
    }
    if (principal instanceof Principal namedPrincipal) {
      return expectedName.equals(namedPrincipal.getName());
    }
    return expectedName.equals(String.valueOf(principal));
  }

  private static String createHash(final String value) {
    try {
      MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
      byte[] digest = messageDigest.digest(value.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("Failed to compute hash for Session ID.", ex);
    }
  }

  /**
   * Bean for JwtDecoder that uses the JWKSource.
   *
   * @param jwkSource the JWKSource bean
   * @return JwtDecoder for decoding JWTs
   */
  @Bean
  @ConditionalOnMissingBean
  public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
    return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
  }
}
