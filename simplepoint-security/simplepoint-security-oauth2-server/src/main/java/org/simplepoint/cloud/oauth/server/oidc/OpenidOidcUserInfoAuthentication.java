/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.cloud.oauth.server.oidc;

import static org.springframework.security.oauth2.jwt.JwtClaimNames.SUB;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.simplepoint.cloud.oauth.server.expansion.oidc.AbstractOidcUserInfoAuthentication;
import org.simplepoint.core.oidc.OidcScopes;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.security.cache.AuthorizationContextCacheable;
import org.simplepoint.security.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcUserInfoAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcUserInfoAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Default implementation for OpenID Connect (OIDC) UserInfo authentication.
 * 默认的 OpenID Connect (OIDC) 用户信息认证实现
 */
public class OpenidOidcUserInfoAuthentication extends AbstractOidcUserInfoAuthentication {

  private final AuthorizationContextCacheable authorizationContextCacheable;

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
  public OpenidOidcUserInfoAuthentication(
      final UsersService usersService,
      AuthorizationContextCacheable authorizationContextCacheable
  ) {
    super(usersService);
    this.authorizationContextCacheable = authorizationContextCacheable;
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
    if (principal != null) {
      claims.put(SUB, principal.getName());
      Collection<GrantedAuthority> scopeAuthorities = principal.getAuthorities();
      if (scopeAuthorities.contains(OidcScopes.getScopeAuthority(OidcScopes.OPENID))) {
        // 如果开启缓存，优先从缓存中获取用户信息
        if (this.authorizationContextCacheable != null) {
          User userContext = this.authorizationContextCacheable.getUserContext(principal.getName(), User.class);
          Collection<String> permission = this.authorizationContextCacheable.getUserPermission(principal.getName());
          if (permission != null) {
            userContext.setPermissions(permission);
            userContext.setAuthorities(permission.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toSet()));
          }
          setClaims(claims, userContext, principal);
          // 否则从用户服务中加载用户信息
        } else {
          UserDetails userDetails = usersService.loadUserByUsername(principal.getName());
          if (userDetails instanceof User user) {
            setClaims(claims, user, principal);
          }
        }
      }
    }

    // Construct OIDC user information from token claims
    // 从令牌声明构造 OIDC 用户信息
    return new OidcUserInfo(claims);
  }

  private void setClaims(Map<String, Object> claims, User user, JwtAuthenticationToken principal) {
    Collection<GrantedAuthority> authorities = principal.getAuthorities();
    if (authorities.contains(OidcScopes.getScopeAuthority(OidcScopes.PROFILE))) {
      claims.put("name", user.getName());
      claims.put("given_name", user.getGivenName());
      claims.put("family_name", user.getFamilyName());
      claims.put("middle_name", user.getMiddleName());
      claims.put("nickname", user.getNickname());
      claims.put("preferred_username", user.getUsername());
    }

    if (authorities.contains(OidcScopes.getScopeAuthority(OidcScopes.EMAIL))) {
      claims.put(OidcScopes.EMAIL, user.getEmail());
      claims.put("email_verified", user.getEmailVerified());
    }

    if (authorities.contains(OidcScopes.getScopeAuthority(OidcScopes.ADDRESS))) {
      claims.put(OidcScopes.ADDRESS, user.getAddress());
    }

    if (authorities.contains(OidcScopes.getScopeAuthority(OidcScopes.PHONE))) {
      claims.put("phone_number", user.getPhoneNumber());
      claims.put("phone_number_verified", user.getPhoneNumberVerified());
    }

    if (authorities.contains(OidcScopes.getScopeAuthority(OidcScopes.ROLES))) {
      claims.put(OidcScopes.PERMISSIONS, user.getPermissions());
      claims.put(OidcScopes.ROLES, user.getAuthorities());
    }

    ObjectNode decorator = user.getDecorator();
    if (decorator != null) {
      if (authorities.contains(OidcScopes.getScopeAuthority(OidcScopes.TENANT))) {
        claims.put(OidcScopes.TENANT, decorator.get(OidcScopes.TENANT));
      }
      if (authorities.contains(OidcScopes.getScopeAuthority(OidcScopes.ORGANS))) {
        claims.put(OidcScopes.ORGANS, decorator.get(OidcScopes.ORGANS));
      }
    }
  }
}
