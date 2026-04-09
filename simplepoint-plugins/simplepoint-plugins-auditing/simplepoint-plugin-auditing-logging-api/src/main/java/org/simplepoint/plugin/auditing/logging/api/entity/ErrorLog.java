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
 * Error log entity.
 */
@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Tag(name = "错误日志", description = "用于记录系统中的警告、错误和异常日志事件")
@Table(name = "simpoint_aud_error_logs", indexes = {
    @Index(name = "idx_error_log_occurred_at", columnList = "occurred_at"),
    @Index(name = "idx_error_log_level", columnList = "level"),
    @Index(name = "idx_error_log_source_service", columnList = "source_service"),
    @Index(name = "idx_error_log_tenant_id", columnList = "tenant_id"),
    @Index(name = "idx_error_log_logger_name", columnList = "logger_name"),
    @Index(name = "idx_error_log_exception_type", columnList = "exception_type")
})
@EqualsAndHashCode(callSuper = true)
public class ErrorLog extends BaseEntityImpl<String> {

  @Order(1)
  @Schema(
      title = "发生时间",
      type = "string",
      format = "date-time",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @Order(2)
  @Schema(
      title = "日志级别",
      maxLength = 16,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(nullable = false, length = 16)
  private String level;

  @Order(3)
  @Schema(
      title = "源服务",
      maxLength = 64,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(name = "source_service", length = 64)
  private String sourceService;

  @Order(4)
  @Schema(
      title = "日志器",
      maxLength = 256,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(name = "logger_name", length = 256)
  private String loggerName;

  @Schema(hidden = true, accessMode = Schema.AccessMode.READ_ONLY)
  @Column(name = "thread_name", length = 128)
  private String threadName;

  @Order(5)
  @Schema(
      title = "日志消息",
      maxLength = 4000,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 4000)
  private String message;

  @Order(6)
  @Schema(
      title = "异常类型",
      maxLength = 256,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(name = "exception_type", length = 256)
  private String exceptionType;

  @Order(7)
  @Schema(
      title = "异常消息",
      maxLength = 4000,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(name = "exception_message", length = 4000)
  private String exceptionMessage;

  @Schema(title = "异常堆栈", maxLength = 16000)
  @Column(name = "stack_trace", length = 16000)
  private String stackTrace;

  @Order(8)
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

  @Order(9)
  @Schema(
      title = "用户ID",
      maxLength = 128,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(name = "user_id", length = 128)
  private String userId;

  @Order(10)
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

  @Order(11)
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
}
