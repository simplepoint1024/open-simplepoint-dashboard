/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.core.context;

import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 表示用户上下文的泛型接口，提供获取用户身份验证信息的方法
 * A generic interface representing the user context, providing methods to retrieve authentication details.
 *
 * @param <T> 继承自 UserDetails 的用户类型
 *            The type of user, extending UserDetails.
 */
public interface UserContext<T extends UserDetails> {

  /**
   * 获取当前身份验证的用户名
   * Retrieves the username of the current authentication.
   *
   * @return 用户名 The username.
   */
  String getName();

  /**
   * 获取当前用户的详细信息
   * Retrieves the details of the currently authenticated user.
   *
   * @return 用户详细信息 The user details.
   */
  T getDetails();

  /**
   * 根据用户名和访问令牌获取用户的详细信息
   * Retrieves the details of a user based on the provided username and access token.
   *
   * @param accessTokenValue 访问令牌 The access token.
   * @return 用户详细信息 The user details.
   */
  T getDetails(String accessTokenValue);

  /**
   * 根据用户名获取用户的详细信息
   * Retrieves the details of a user based on the provided username.
   *
   * @param username 用户名 The username.
   * @return 用户详细信息 The user details.
   */
  T getDetailsByUsername(String username);

  /**
   * 根据用户名获取用户的权限集合
   * Retrieves the set of permissions associated with the given username.
   *
   * @param username 用户名 The username.
   * @return 权限集合 The set of permissions.
   */
  Set<String> getPermissionsByUsername(String username);

  /**
   * 获取当前用户的权限集合
   * Retrieves the set of permissions for the currently authenticated user.
   *
   * @return 权限集合 The set of permissions.
   */
  Set<String> getPermissions();

  /**
   * 获取当前的身份验证对象
   * Retrieves the current authentication object.
   *
   * @return 身份验证对象 The authentication object.
   */
  Authentication getAuthentication();

  /**
   * 检查当前用户是否为超级管理员
   * Checks if the current user is a super administrator.
   *
   * @return 如果是超级管理员则返回 true，否则返回 false
   *         Returns true if the user is a super administrator, false otherwise.
   */
  boolean isSuperAdmin();
}

