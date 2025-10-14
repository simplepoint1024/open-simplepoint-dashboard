/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.cloud.oauth.server.oidc;

import static org.springframework.security.oauth2.jwt.JwtClaimNames.SUB;

import java.util.Collection;
import java.util.LinkedHashMap;
import org.simplepoint.cloud.oauth.server.expansion.oidc.AbstractOidcUserInfoAuthentication;
import org.simplepoint.core.oidc.OidcScopes;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.security.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcUserInfoAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcUserInfoAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Default implementation for OpenID Connect (OIDC) UserInfo authentication.
 * 默认的 OpenID Connect (OIDC) 用户信息认证实现
 */
public class DefaultOidcUserInfoAuthentication extends AbstractOidcUserInfoAuthentication {

  /**
   * Constructs a default implementation of OIDC user info authentication.
   * Calls the superclass constructor to initialize the user service dependency.
   *
   * <p>构造 OIDC 用户信息认证的默认实现
   * 调用父类构造方法以初始化用户服务依赖</p>
   *
   * @param usersService The service responsible for managing user information.
   *                     用户服务，负责管理用户信息
   */
  public DefaultOidcUserInfoAuthentication(final UsersService usersService) {
    super(usersService);
  }


  /**
   * Applies the authentication context to extract OIDC user information.
   * 应用认证上下文以提取 OIDC 用户信息
   *
   * @param context the authentication context 认证上下文
   * @return OIDC user information OIDC 用户信息
   */
  @Override
  public OidcUserInfo apply(OidcUserInfoAuthenticationContext context) {
    var claims = new LinkedHashMap<String, Object>();
    // Retrieve authentication token from the context
    // 从上下文中获取认证令牌
    OidcUserInfoAuthenticationToken authentication = context.getAuthentication();

    // Extract JWT authentication token
    // 提取 JWT 认证令牌
    JwtAuthenticationToken principal = (JwtAuthenticationToken) authentication.getPrincipal();
    claims.put(SUB, principal.getName());
    Collection<GrantedAuthority> authorities = principal.getAuthorities();

    if (authorities.contains(OidcScopes.getScopeAuthority(OidcScopes.PROFILE))) {
      UserDetails userDetails = usersService.loadUserByUsername(principal.getName());
      if (userDetails instanceof User user) {
        claims.put("name", user.getName());
        claims.put("picture", user.getPicture());
        claims.put("gender", user.getGender());
        claims.put("locale", user.getLocale());
        claims.put("zoneinfo", user.getZoneinfo());
        claims.put("middle_name", user.getMiddleName());
        claims.put("nickname", user.getNickname());
        claims.put("given_name", user.getGivenName());
        claims.put("super_admin", user.getSuperAdmin());
        if (authorities.contains(OidcScopes.getScopeAuthority(OidcScopes.EMAIL))) {
          claims.put("email", user.getEmail());
        }
        if (authorities.contains(OidcScopes.getScopeAuthority(OidcScopes.PHONE))) {
          claims.put("phone", user.getPhoneNumber());
        }
        if (authorities.contains(OidcScopes.getScopeAuthority(OidcScopes.ADDRESS))) {
          claims.put("address", user.getAddress());
        }
        if (user.getAuthorities() != null
            && authorities.contains(OidcScopes.getScopeAuthority(OidcScopes.ROLES))) {
          claims.put("roles",
              user.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList());
        }
      }
    }

    // Construct OIDC user information from token claims
    // 从令牌声明构造 OIDC 用户信息
    return new OidcUserInfo(claims);
  }
}
