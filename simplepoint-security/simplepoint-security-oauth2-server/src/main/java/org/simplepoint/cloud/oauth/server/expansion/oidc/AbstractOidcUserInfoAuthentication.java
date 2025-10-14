/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.cloud.oauth.server.expansion.oidc;

import org.simplepoint.plugin.rbac.core.api.service.UsersService;

/**
 * Abstract implementation of OIDC user info authentication expansion.
 * Provides a base class for handling user authentication with {@link UsersService}.
 * OIDC 用户信息认证扩展的抽象实现
 * 提供一个用于处理用户认证的基类，依赖于 {@link UsersService}
 */
public abstract class AbstractOidcUserInfoAuthentication implements
    OidcUserInfoAuthenticationExpansion {

  /**
   * Users service dependency for retrieving user information.
   * 用户服务，用于获取用户信息
   */
  protected final UsersService usersService;

  /**
   * Constructs an authentication expansion instance using the specified user service.
   * 构造一个用户认证扩展实例，使用指定的用户服务
   *
   * @param usersService The service responsible for managing user information.
   *                     用户服务，负责管理用户信息
   */
  protected AbstractOidcUserInfoAuthentication(UsersService usersService) {
    this.usersService = usersService;
  }
}

