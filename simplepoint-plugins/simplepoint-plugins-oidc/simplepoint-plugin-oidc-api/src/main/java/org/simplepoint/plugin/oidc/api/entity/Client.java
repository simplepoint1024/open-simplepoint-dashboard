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
import org.simplepoint.core.constants.Icons;
import org.simplepoint.core.constants.PublicButtonKeys;
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
        title = PublicButtonKeys.ADD_TITLE,
        key = PublicButtonKeys.ADD_KEY,
        icon = Icons.PLUS_CIRCLE,
        sort = 0,
        argumentMaxSize = 1,
        argumentMinSize = 0,
        authority = "menu:clients:add"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "menu:clients:edit"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.DELETE_TITLE,
        key = PublicButtonKeys.DELETE_KEY,
        color = "danger",
        icon = Icons.MINUS_CIRCLE,
        sort = 2,
        argumentMinSize = 1,
        argumentMaxSize = 10,
        danger = true,
        authority = "menu:clients:delete"
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
  @Schema(title = "i18n:clients.title.clientId", description = "i18n:clients.description.clientId", extensions = {
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
  @Schema(
      title = "i18n:clients.title.clientIdIssuedAt",
      description = "i18n:clients.description.clientIdIssuedAt",
      type = "string",
      format = "date-time"
  )
  private Instant clientIdIssuedAt;

  /**
   * The client secret used for authentication in confidential OAuth2 flows.
   */
  @Order(2)
  @Column(unique = true, nullable = false)
  @Schema(title = "i18n:clients.title.clientSecret", description = "i18n:clients.description.clientSecret", extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "widget", value = "password"),
      })
  })
  private String clientSecret;

  /**
   * Timestamp indicating when the client secret expires.
   */
  @Order(9)
  @Schema(
      title = "i18n:clients.title.clientSecretExpiresAt",
      description = "i18n:clients.description.clientSecretExpiresAt",
      type = "string",
      format = "date-time"
  )
  private Instant clientSecretExpiresAt;

  /**
   * Human-readable name assigned to the client.
   */
  @Order(1)
  @Schema(title = "i18n:clients.title.clientName", description = "i18n:clients.description.clientName", extensions = {
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
  @Schema(
      title = "i18n:clients.title.clientAuthenticationMethods",
      description = "i18n:clients.description.clientAuthenticationMethods",
      extensions = {
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
  @Schema(title = "i18n:clients.title.authorizationGrantTypes", description = "i18n:clients.description.authorizationGrantTypes", extensions = {
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
  @Schema(title = "i18n:clients.title.redirectUris", description = "i18n:clients.description.redirectUris")
  private String redirectUris;

  /**
   * Allowed post-logout redirect URIs for OpenID Connect logout flows.
   */
  @Order(6)
  @Column(length = 1000)
  @Schema(title = "i18n:clients.title.postLogoutRedirectUris", description = "i18n:clients.description.postLogoutRedirectUris")
  private String postLogoutRedirectUris;

  /**
   * Scopes granted to the client (e.g., read, write, profile).
   */
  @Order(7)
  @Column(length = 1000)
  @Schema(title = "i18n:clients.title.scopes", description = "i18n:clients.description.scopes", extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  private String scopes;

  /**
   * JSON-encoded client settings (e.g., requiring consent, token endpoint authentication).
   */
  @Column(length = 2000)
  @Schema(title = "i18n:clients.title.clientSettings", description = "i18n:clients.description.clientSettings", extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "widget", value = "textarea"),
      })
  })
  private String clientSettings;

  /**
   * JSON-encoded token settings (e.g., access token lifetime, refresh token policies).
   */
  @Column(length = 2000)
  @Schema(title = "i18n:clients.title.tokenSettings", description = "i18n:clients.description.tokenSettings", extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "widget", value = "textarea"),
      })
  })
  private String tokenSettings;
}