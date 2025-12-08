/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.cloud.oauth.server.configuration;

import java.util.Map;
import java.util.Set;
import org.simplepoint.cloud.oauth.server.expansion.oidc.OidcConfigurerExpansion;
import org.simplepoint.cloud.oauth.server.expansion.oidc.OidcUserInfoAuthenticationExpansion;
import org.simplepoint.cloud.oauth.server.oidc.DefaultOidcConfigurerExpansion;
import org.simplepoint.cloud.oauth.server.oidc.OpenidOidcUserInfoAuthentication;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.security.cache.AuthorizationContextCacheable;
import org.simplepoint.security.decorator.TokenDecorator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

/**
 * Configuration class for OpenID Connect (OIDC) settings.
 * OpenID Connect (OIDC) 设置的配置类
 */
@Configuration
public class OidcConfiguration {

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
      final OidcUserInfoAuthenticationExpansion oidcUserInfoAuthenticationExpansion
  ) {
    return new DefaultOidcConfigurerExpansion(oidcUserInfoAuthenticationExpansion);
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
      final UsersService usersService,
      @Autowired(required = false) final AuthorizationContextCacheable authorizationContextCacheable,
      Set<TokenDecorator> accessTokenDecorators
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
    return new OpenidOidcUserInfoAuthentication(usersService, authorizationContextCacheable);
  }

  /**
   * Customizes the JWT token claims.
   * 定制 JWT 令牌声明
   *
   * @return the OAuth2 token customizer OAuth2 令牌定制器
   */
  @Bean
  public OAuth2TokenCustomizer<JwtEncodingContext> jwtCustomizer(
      final Set<TokenDecorator> tokenDecorator
  ) {
    return context -> {
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

}