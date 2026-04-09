/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.ratelimit.api.entity;

import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.core.constants.Icons;
import org.simplepoint.core.constants.PublicButtonKeys;
import org.springframework.core.annotation.Order;

/**
 * Endpoint-level gateway rate-limit rule.
 */
@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@ButtonDeclarations({
    @ButtonDeclaration(
        title = PublicButtonKeys.ADD_TITLE,
        key = PublicButtonKeys.ADD_KEY,
        icon = Icons.PLUS_CIRCLE,
        sort = 0,
        argumentMinSize = 0,
        argumentMaxSize = 1,
        authority = "endpoint.rate.limit.rules.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "endpoint.rate.limit.rules.edit"
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
        authority = "endpoint.rate.limit.rules.delete"
    )
})
@Tag(name = "接口限流规则", description = "用于配置网关针对具体接口路径的访问限流规则")
@Table(
    name = "simpoint_aud_endpoint_rate_limit_rules",
    indexes = {
        @Index(name = "idx_endpoint_rate_limit_rule_service_id", columnList = "service_id"),
        @Index(name = "idx_endpoint_rate_limit_rule_path_pattern", columnList = "path_pattern"),
        @Index(name = "idx_endpoint_rate_limit_rule_enabled", columnList = "enabled"),
        @Index(name = "idx_endpoint_rate_limit_rule_sort", columnList = "sort")
    }
)
@EqualsAndHashCode(callSuper = true)
public class EndpointRateLimitRule extends BaseEntityImpl<String> {

  @Order(0)
  @Schema(
      title = "规则名称",
      description = "用于标识接口限流规则的显示名称",
      example = "Authorization Token Endpoint Limit",
      maxLength = 128,
      minLength = 1,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 128, nullable = false)
  private String name;

  @Order(1)
  @Schema(
      title = "服务标识",
      description = "该接口所属的下游服务标识",
      example = "authorization",
      maxLength = 128,
      minLength = 1,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(name = "service_id", length = 128, nullable = false)
  private String serviceId;

  @Order(2)
  @Schema(
      title = "路径匹配",
      description = "基于 Ant 风格的路径匹配表达式",
      example = "/authorization/oauth2/token",
      maxLength = 512,
      minLength = 1,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(name = "path_pattern", length = 512, nullable = false)
  private String pathPattern;

  @Order(3)
  @Schema(
      title = "HTTP 方法",
      description = "留空表示匹配所有方法，否则按 GET、POST 等方法名匹配",
      example = "POST",
      maxLength = 16,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(name = "http_method", length = 16)
  private String httpMethod;

  @Order(4)
  @Schema(
      title = "匹配顺序",
      description = "数值越小优先级越高",
      example = "0",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  private Integer sort = 0;

  @Order(5)
  @Schema(
      title = "限流键策略",
      description = "支持 GLOBAL、CLIENT_IP、USER_ID、TENANT_ID",
      example = "CLIENT_IP",
      maxLength = 32,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(name = "key_strategy", length = 32, nullable = false)
  private String keyStrategy;

  @Order(6)
  @Schema(
      title = "补充速率",
      description = "每秒补充到令牌桶中的令牌数量",
      example = "10",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(name = "replenish_rate", nullable = false)
  private Integer replenishRate;

  @Order(7)
  @Schema(
      title = "突发容量",
      description = "令牌桶最大容量，必须大于等于补充速率",
      example = "20",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(name = "burst_capacity", nullable = false)
  private Long burstCapacity;

  @Order(8)
  @Schema(
      title = "单次消耗令牌",
      description = "每个请求消耗的令牌数",
      example = "1",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(name = "requested_tokens", nullable = false)
  private Integer requestedTokens = 1;

  @Order(9)
  @Schema(
      title = "是否启用",
      description = "控制该接口限流规则是否生效",
      example = "true",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(nullable = false)
  private Boolean enabled = Boolean.TRUE;

  @Order(10)
  @Schema(
      title = "规则说明",
      description = "用于补充说明限流规则用途",
      example = "Limit token issuance endpoint by client IP",
      maxLength = 512,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 512)
  private String description;
}
