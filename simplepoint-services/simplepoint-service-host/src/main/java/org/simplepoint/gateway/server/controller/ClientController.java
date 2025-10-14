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
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

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
  @GetMapping("/login")
  @Operation(summary = "登录页面", description = "返回登录页面")
  public String login() {
    return "login";
  }

  /**
   * Retrieves OAuth2 authentication token, providing user authentication details.
   *
   * @param principal OAuth2 authentication principal
   * @return the OAuth2AuthenticationToken containing user information
   */
  @GetMapping("/")
  @Operation(summary = "获取用户信息", description = "获取OAuth2认证令牌，提供用户认证信息")
  public String getUserInfo(
      @AuthenticationPrincipal OAuth2AuthenticatedPrincipal principal,
      @RegisteredOAuth2AuthorizedClient OAuth2AuthorizedClient authorizedClient
  ) {
    log.debug("Principal : {}", principal.toString());
    log.debug("Access-Token : {}", authorizedClient.getAccessToken().getTokenValue());
    log.debug("Refresh Token : {}", Objects.requireNonNull(authorizedClient.getRefreshToken()).getTokenValue());
    return "redirect:index.html";
  }
}
