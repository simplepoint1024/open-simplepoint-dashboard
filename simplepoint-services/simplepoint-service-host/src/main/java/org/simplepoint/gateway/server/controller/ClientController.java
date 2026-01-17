/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.gateway.server.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * ClientController handles login and user authentication information retrieval.
 */
@Slf4j
@Controller
@Tag(name = "OAuth2.1客户端", description = "OAuth2.1 客户端信息获取相关接口")
public class ClientController {

  /**
   * Ignore Chrome DevTools / other browser probes under .well-known/appspecific.
   * Return 204 so browser认为探测成功，不再干扰业务路由 / 日志。
   */
  @RequestMapping("/.well-known/appspecific/**")
  public ResponseEntity<Void> ignoreChromeProbe() {
    return ResponseEntity.noContent().build(); // 204 No Content
  }

  /**
   * Handles login requests and redirects to Spring Security's OAuth2 authorization entry.
   *
   * @param registrationId client registration id
   */
  @Operation(summary = "登录入口重定向", description = "根据 registrationId 重定向到 /oauth2/authorization/{registrationId}")
  @GetMapping("/{registrationId}/authorize")
  public Mono<Void> authorize(@PathVariable("registrationId") String registrationId,
                              ServerWebExchange exchange) {

    // 可选：白名单校验，避免恶意 registrationId
    Set<String> allowedClients = Set.of("simplepoint", "another-client-id");
    if (!allowedClients.contains(registrationId)) {
      log.warn("Illegal registrationId access attempt: {}", registrationId);
      exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
      return exchange.getResponse().setComplete();
    }

    return exchange.getSession().flatMap(session -> {
      // 这里可以绑定 tenant 等信息到 session
      // session.getAttributes().put("tenant", resolvedTenant);

      ServerHttpResponse response = exchange.getResponse();
      response.setStatusCode(HttpStatus.FOUND);
      response.getHeaders().setLocation(URI.create("/oauth2/authorization/" + registrationId));
      return response.setComplete();
    });
  }

  /**
   * Root endpoint: after successful authentication, redirect to index.html.
   * Only authenticated users should reach here (由 Spring Security 保护 / 配置).
   */
  @GetMapping("/")
  @Operation(summary = "根路径重定向", description = "认证成功后重定向到 index.html")
  public String userinfo(
      @AuthenticationPrincipal OAuth2AuthenticatedPrincipal principal,
      @RegisteredOAuth2AuthorizedClient OAuth2AuthorizedClient authorizedClient
  ) {
    if (principal == null || authorizedClient == null) {
      // 理论上由 Spring Security 保证不会发生，防御性处理
      log.warn("Access to '/' without valid principal or authorizedClient");
      return "redirect:/login";
    }

    // 不打印完整 Token，最多打印部分前缀用于调试
    String accessTokenValue = authorizedClient.getAccessToken().getTokenValue();
    String accessTokenPreview = accessTokenValue.substring(0, Math.min(10, accessTokenValue.length()));
    log.debug("Principal: {}", principal.getName());
    log.debug("Access Token (preview): {}******", accessTokenPreview);

    if (authorizedClient.getRefreshToken() != null) {
      String refreshTokenValue = authorizedClient.getRefreshToken().getTokenValue();
      String refreshTokenPreview = refreshTokenValue.substring(0, Math.min(10, refreshTokenValue.length()));
      log.debug("Refresh Token (preview): {}******", refreshTokenPreview);
    }

    return "redirect:index.html";
  }

  /**
   * Returns the login page view.
   *
   * @param error optional error code/message from Spring Security
   * @return the login view name
   */
  @GetMapping("/login")
  @Operation(summary = "登录页面", description = "返回登录页面")
  public String login(@RequestParam(value = "error", required = false) String error,
                      Model model) {
    if (error != null) {
      // 这里可以根据 error code 映射成更友好的提示
      model.addAttribute("loginError", true);
      model.addAttribute("errorMessage", "Invalid username, password or authorization request.");
      log.debug("Login error: {}", error);
    }
    return "login";
  }
}
