/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.cloud.oauth.server.oidc;

import org.simplepoint.cloud.oauth.server.expansion.oidc.AbstractOidcConfigurerExpansion;
import org.simplepoint.cloud.oauth.server.expansion.oidc.OidcUserInfoAuthenticationExpansion;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OidcConfigurer;

/**
 * Default implementation for OIDC configuration expansion.
 * OIDC 配置扩展的默认实现
 */
public class DefaultOidcConfigurerExpansion extends AbstractOidcConfigurerExpansion {

  /**
   * The OIDC user info authentication expansion.
   * OIDC 用户信息认证扩展
   */
  private final OidcUserInfoAuthenticationExpansion oidcUserInfoAuthenticationExpansion;

  /**
   * Constructs a DefaultOidcConfigurerExpansion with the specified user info authentication expansion.
   * 使用指定的用户信息认证扩展构造 DefaultOidcConfigurerExpansion
   *
   * @param oidcUserInfoAuthenticationExpansion the OIDC user info authentication expansion
   *                                            OIDC 用户信息认证扩展
   */
  public DefaultOidcConfigurerExpansion(
      OidcUserInfoAuthenticationExpansion oidcUserInfoAuthenticationExpansion) {
    this.oidcUserInfoAuthenticationExpansion = oidcUserInfoAuthenticationExpansion;
  }

  /**
   * Customizes the OIDC configuration by setting the user info endpoint.
   * 通过设置用户信息端点来自定义 OIDC 配置
   *
   * @param oidc the OIDC configurer OIDC 配置器
   */
  @Override
  public void customize(OidcConfigurer oidc) {
    // Configure the user info endpoint mapper
    // 配置用户信息端点映射器
    oidc.userInfoEndpoint(endpointConfigurer ->
        endpointConfigurer.userInfoMapper(oidcUserInfoAuthenticationExpansion)
    );
  }
}
