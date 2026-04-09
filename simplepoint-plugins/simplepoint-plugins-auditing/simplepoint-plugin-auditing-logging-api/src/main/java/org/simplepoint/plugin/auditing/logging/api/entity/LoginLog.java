/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.logging.api.entity;

import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.springframework.core.annotation.Order;

/**
 * Login log entity.
 */
@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Tag(name = "登录日志", description = "用于记录系统中的登录成功与失败事件")
@Table(name = "simpoint_aud_login_logs", indexes = {
    @Index(name = "idx_login_log_username", columnList = "username"),
    @Index(name = "idx_login_log_status", columnList = "status"),
    @Index(name = "idx_login_log_tenant_id", columnList = "tenant_id"),
    @Index(name = "idx_login_log_login_at", columnList = "login_at")
})
@EqualsAndHashCode(callSuper = true)
public class LoginLog extends BaseEntityImpl<String> {

  @Order(1)
  @Schema(
      title = "登录时间",
      type = "string",
      format = "date-time",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(name = "login_at", nullable = false)
  private Instant loginAt;

  @Order(2)
  @Schema(
      title = "登录状态",
      maxLength = 16,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(nullable = false, length = 16)
  private String status;

  @Order(3)
  @Schema(
      title = "登录方式",
      maxLength = 32,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 32)
  private String loginType;

  @Order(4)
  @Schema(
      title = "登录账号",
      maxLength = 128,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 128)
  private String username;

  @Order(5)
  @Schema(
      title = "显示名称",
      maxLength = 128,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 128)
  private String displayName;

  @Schema(hidden = true, accessMode = Schema.AccessMode.READ_ONLY)
  @Column(length = 64)
  private String userId;

  @Order(6)
  @Schema(
      title = "租户ID",
      maxLength = 64,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(name = "tenant_id", length = 64)
  private String tenantId;

  @Schema(hidden = true, accessMode = Schema.AccessMode.READ_ONLY)
  @Column(name = "context_id", length = 64)
  private String contextId;

  @Order(7)
  @Schema(
      title = "客户端IP",
      maxLength = 64,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(name = "client_ip", length = 64)
  private String clientIp;

  @Schema(hidden = true, accessMode = Schema.AccessMode.READ_ONLY)
  @Column(name = "remote_address", length = 64)
  private String remoteAddress;

  @Schema(hidden = true, accessMode = Schema.AccessMode.READ_ONLY)
  @Column(name = "forwarded_for", length = 512)
  private String forwardedFor;

  @Schema(hidden = true, accessMode = Schema.AccessMode.READ_ONLY)
  @Column(name = "user_agent", length = 2048)
  private String userAgent;

  @Order(8)
  @Schema(
      title = "请求地址",
      maxLength = 512,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(name = "request_uri", length = 512)
  private String requestUri;

  @Schema(hidden = true, accessMode = Schema.AccessMode.READ_ONLY)
  @Column(name = "session_id", length = 128)
  private String sessionId;

  @Schema(hidden = true, accessMode = Schema.AccessMode.READ_ONLY)
  @Column(name = "authentication_type", length = 128)
  private String authenticationType;

  @Schema(hidden = true, accessMode = Schema.AccessMode.READ_ONLY)
  @Column(name = "source_service", length = 64)
  private String sourceService;

  @Order(9)
  @Schema(
      title = "失败原因",
      maxLength = 512,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(name = "failure_reason", length = 512)
  private String failureReason;
}
