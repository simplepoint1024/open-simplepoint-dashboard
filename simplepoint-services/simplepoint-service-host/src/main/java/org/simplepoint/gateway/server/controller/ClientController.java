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
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
   * Handles login requests and returns the login page.
   *
   * @return the login view name
   */
  @Operation(summary = "登录页面", description = "返回登录页面")
  @GetMapping("/{registrationId}/authorize")
  public Mono<Void> authorize(@PathVariable("registrationId") String registrationId, ServerWebExchange exchange) {
    // 可在此解析并绑定 tenant 到 session
    return exchange.getSession().flatMap(session -> {
      // session.getAttributes().put("tenant", resolvedTenant);
      // 直接重定向到 Spring 的默认启动路径，后续 resolver 会注入 PKCE
      ServerHttpResponse response = exchange.getResponse();
      response.setStatusCode(HttpStatus.FOUND);
      response.getHeaders().setLocation(URI.create("/oauth2/authorization/" + registrationId));
      return response.setComplete();
    });
  }

  /**
   * Retrieves OAuth2 authentication token, providing user authentication details.
   *
   * @param principal OAuth2 authentication principal
   * @return the OAuth2AuthenticationToken containing user information
   */
  @GetMapping("/")
  @Operation(summary = "/", description = "/")
  public String userinfo(
      @AuthenticationPrincipal OAuth2AuthenticatedPrincipal principal,
      @RegisteredOAuth2AuthorizedClient OAuth2AuthorizedClient authorizedClient
  ) {
    log.debug("Principal : {}", principal.toString());
    log.info("Access-Token : {}", authorizedClient.getAccessToken().getTokenValue());
    log.debug("Refresh Token : {}", Objects.requireNonNull(authorizedClient.getRefreshToken()).getTokenValue());
    return "redirect:index.html";
  }

  /**
   * Returns the login page view.
   *
   * @return the login view name
   */
  @GetMapping("/login")
  @Operation(summary = "登录页面", description = "返回登录页面")
  public String login(@RequestParam(value = "error", required = false) String error) {
    return "login";
  }
}
