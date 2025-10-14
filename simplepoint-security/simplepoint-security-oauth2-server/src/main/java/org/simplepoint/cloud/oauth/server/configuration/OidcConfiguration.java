/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.cloud.oauth.server.configuration;

import org.simplepoint.cloud.oauth.server.expansion.oidc.OidcConfigurerExpansion;
import org.simplepoint.cloud.oauth.server.expansion.oidc.OidcUserInfoAuthenticationExpansion;
import org.simplepoint.cloud.oauth.server.oidc.DefaultOidcConfigurerExpansion;
import org.simplepoint.cloud.oauth.server.oidc.DefaultOidcUserInfoAuthentication;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
  public OidcUserInfoAuthenticationExpansion oidcUserInfoAuthentication(final UsersService usersService) {
    /*
     * Uses {@link UsersService} as a dependency for user info authentication.
     * 使用 {@link UsersService} 作为用户信息认证服务的依赖项
     *
     * @param usersService The user service that provides user information retrieval.
     *                     用户服务，提供用户信息获取功能
     * @return The default implementation of OIDC user info authentication expansion.
     *         默认的 OIDC 用户信息认证扩展实现
     */
    return new DefaultOidcUserInfoAuthentication(usersService);
  }
}