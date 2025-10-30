/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.oidc.api.entity;

import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.springframework.core.annotation.Order;


/**
 * Entity representing an OAuth2 client.
 *
 * <p>This class stores OAuth2 client configuration details, including authentication methods,
 * authorization grant types, and various token settings. The data is persisted for managing
 * registered clients in an authorization server.
 * </p>
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Entity
@ButtonDeclarations({
    @ButtonDeclaration(
        title = "添加", key = "add", icon = "PlusCircleOutlined", sort = 0, argumentMaxSize = 0, argumentMinSize = 0
    ),
    @ButtonDeclaration(
        title = "编辑", key = "edit", color = "orange", icon = "EditOutlined", sort = 1,
        argumentMinSize = 1, argumentMaxSize = 1
    ),
    @ButtonDeclaration(
        title = "删除", key = "delete", color = "danger", icon = "MinusCircleOutlined", sort = 2,
        argumentMinSize = 1, argumentMaxSize = 10, danger = true
    )
})
@Table(name = "security_oauth2_client")
@Tag(name = "OAuth2客户端对象", description = "用于管理系统中的OAuth2客户端")
public class Client extends BaseEntityImpl<String> {

  /**
   * The client identifier used in OAuth2 authentication flows.
   */
  @Order(0)
  @Column(unique = true, nullable = false)
  @Schema(title = "Client ID", description = "客户端的唯一标识", extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  private String clientId;

  /**
   * Timestamp indicating when the client ID was issued.
   */
  @Order(8)
  @Column(unique = true, nullable = false)
  @Schema(title = "有效时间", description = "客户端的有效时间", type = "string", format = "date-time")
  private Instant clientIdIssuedAt;

  /**
   * The client secret used for authentication in confidential OAuth2 flows.
   */
  @Order(2)
  @Column(unique = true, nullable = false)
  @Schema(title = "Secret", description = "客户端的秘钥", extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "widget", value = "password"),
      })
  })
  private String clientSecret;

  /**
   * Timestamp indicating when the client secret expires.
   */
  @Order(9)
  @Schema(title = "秘钥有效期", description = "过期后就是失效了", type = "string", format = "date-time")
  private Instant clientSecretExpiresAt;

  /**
   * Human-readable name assigned to the client.
   */
  @Order(1)
  @Schema(title = "客户端名称", description = "客户端的名字", extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  private String clientName;

  /**
   * Authentication methods supported by the client (e.g., client_secret_basic, client_secret_post).
   */
  @Order(3)
  @Column(length = 1000)
  @Schema(title = "认证方式", description = "客户端支持的认证方式", extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  private String clientAuthenticationMethods;

  /**
   * Supported OAuth2 authorization grant types (e.g., authorization_code, client_credentials).
   */
  @Order(4)
  @Column(length = 1000)
  @Schema(title = "授权模式", description = "客户端支持的授权模式", extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  private String authorizationGrantTypes;

  /**
   * Allowed redirect URIs for authorization responses.
   */
  @Order(5)
  @Column(length = 1000)
  @Schema(title = "重定向地址", description = "如果是多个请使用英文逗号分隔")
  private String redirectUris;

  /**
   * Allowed post-logout redirect URIs for OpenID Connect logout flows.
   */
  @Order(6)
  @Column(length = 1000)
  @Schema(title = "登出重定向地址", description = "如果是多个请使用英文逗号分隔")
  private String postLogoutRedirectUris;

  /**
   * Scopes granted to the client (e.g., read, write, profile).
   */
  @Order(7)
  @Column(length = 1000)
  @Schema(title = "授权范围", description = "如果是多个请使用英文逗号分隔", extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  private String scopes;

  /**
   * JSON-encoded client settings (e.g., requiring consent, token endpoint authentication).
   */
  @Column(length = 2000)
  @Schema(title = "客户端设置", description = "JSON格式存储客户端的相关设置", extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "widget", value = "textarea"),
      })
  })
  private String clientSettings;

  /**
   * JSON-encoded token settings (e.g., access token lifetime, refresh token policies).
   */
  @Column(length = 2000)
  @Schema(title = "令牌设置", description = "JSON格式存储令牌的相关设置", extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "widget", value = "textarea"),
      })
  })
  private String tokenSettings;
}