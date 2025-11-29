/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.cloud.oauth.server.configuration;

import lombok.extern.slf4j.Slf4j;
import org.simplepoint.cloud.oauth.server.expansion.oidc.OidcConfigurerExpansion;
import org.simplepoint.cloud.oauth.server.handler.LoginAuthenticationSuccessHandler;
import org.simplepoint.security.cache.AuthorizationContextCacheable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

/**
 * Configuration class for the authorization server.
 * 授权服务器的配置类
 */
@Slf4j
@Configuration
@EnableWebSecurity
public class AuthorizationServerConfiguration {

  private final AuthorizationContextCacheable authorizationContextCacheable;

  /**
   * Constructs the AuthorizationServerConfiguration with an optional
   * AuthorizationContextCacheable dependency.
   *
   * @param authorizationContextCacheable the authorization context cacheable
   *                                      授权上下文缓存接口
   */
  public AuthorizationServerConfiguration(
      @Autowired(required = false)
      AuthorizationContextCacheable authorizationContextCacheable
  ) {
    this.authorizationContextCacheable = authorizationContextCacheable;
  }

  /**
   * Defines the security filter chain for the authorization server.
   * 定义授权服务器的安全过滤链
   *
   * @param http                    the HTTP security configuration HTTP 安全配置
   * @param oidcConfigurerExpansion OIDC configuration expansion OIDC 配置扩展
   * @return the security filter chain 安全过滤链
   * @throws Exception if an error occurs 如果发生错误
   */
  @Bean
  @Order(1)
  public SecurityFilterChain authorizationServerSecurityFilterChain(
      final HttpSecurity http,
      final OidcConfigurerExpansion oidcConfigurerExpansion
  )
      throws Exception {
    // Configure OAuth2 Authorization Server
    // 配置 OAuth2 授权服务器
    OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
        OAuth2AuthorizationServerConfigurer.authorizationServer();
    http.csrf(AbstractHttpConfigurer::disable);
    http
        .securityMatcher(authorizationServerConfigurer.getEndpointsMatcher())
        .with(authorizationServerConfigurer, (authorizationServer) ->
            authorizationServer
                .oidc(oidcConfigurerExpansion)
        )
        .authorizeHttpRequests((authorize) ->
            // Require authentication for all requests 需要认证所有请求
            authorize.anyRequest().authenticated()
        )
        .exceptionHandling((exceptions) -> exceptions
            .defaultAuthenticationEntryPointFor(
                new LoginUrlAuthenticationEntryPoint("/login"),
                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)
            )
        );

    return http.build();
  }

  /**
   * Defines the default security filter chain.
   * 定义默认的安全过滤链
   *
   * @param http the HTTP security configuration HTTP 安全配置
   * @return the security filter chain 安全过滤链
   * @throws Exception if an error occurs 如果发生错误
   */
  @Bean
  @Order(2)
  public SecurityFilterChain defaultSecurityFilterChain(
      final HttpSecurity http
  )
      throws Exception {
    http
        .authorizeHttpRequests(
            authorize -> authorize.requestMatchers(
                    "/actuator/**", "/static/**", "/webjars/**", "/favicon.ico", "/assets/**",
                    "/v3/api-docs/**", "/swagger-ui/**", "/error", "/css/**", "/js/**", "/images/**"
                ).permitAll().anyRequest()
                .authenticated())
        .formLogin(configurer -> {
          configurer.loginPage("/login").permitAll();
          if (authorizationContextCacheable != null) {
            log.info("Lodding LoginAuthenticationSuccessHandler with AuthorizationContextCacheable");
            configurer.successHandler(new LoginAuthenticationSuccessHandler(authorizationContextCacheable));
          }
        });

    return http.build();
  }

  /**
   * Provides the authorization server settings.
   * 提供授权服务器设置
   *
   * @return the authorization server settings 授权服务器设置
   */
  @Bean
  public AuthorizationServerSettings authorizationServerSettings() {
    return AuthorizationServerSettings.builder().build();
  }
}
