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
 * Permission change log entity.
 */
@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Tag(name = "权限变更记录", description = "用于记录系统中的授权关系变更事件")
@Table(name = "simpoint_aud_permission_change_logs", indexes = {
    @Index(name = "idx_permission_change_log_changed_at", columnList = "changed_at"),
    @Index(name = "idx_permission_change_log_change_type", columnList = "change_type"),
    @Index(name = "idx_permission_change_log_action", columnList = "action"),
    @Index(name = "idx_permission_change_log_tenant_id", columnList = "tenant_id"),
    @Index(name = "idx_permission_change_log_operator_id", columnList = "operator_id")
})
@EqualsAndHashCode(callSuper = true)
public class PermissionChangeLog extends BaseEntityImpl<String> {

  @Order(1)
  @Schema(
      title = "变更时间",
      type = "string",
      format = "date-time",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(name = "changed_at", nullable = false)
  private Instant changedAt;

  @Order(2)
  @Schema(
      title = "变更类型",
      maxLength = 64,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(name = "change_type", nullable = false, length = 64)
  private String changeType;

  @Order(3)
  @Schema(
      title = "操作类型",
      maxLength = 32,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(nullable = false, length = 32)
  private String action;

  @Order(4)
  @Schema(
      title = "主体类型",
      maxLength = 32,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(name = "subject_type", nullable = false, length = 32)
  private String subjectType;

  @Order(5)
  @Schema(
      title = "主体标识",
      maxLength = 128,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(name = "subject_id", nullable = false, length = 128)
  private String subjectId;

  @Order(6)
  @Schema(
      title = "主体名称",
      maxLength = 256,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(name = "subject_label", length = 256)
  private String subjectLabel;

  @Order(7)
  @Schema(
      title = "目标类型",
      maxLength = 32,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(name = "target_type", nullable = false, length = 32)
  private String targetType;

  @Order(8)
  @Schema(
      title = "变更目标",
      maxLength = 2048,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(name = "target_summary", length = 2048)
  private String targetSummary;

  @Order(9)
  @Schema(
      title = "目标数量",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(name = "target_count")
  private Integer targetCount;

  @Order(10)
  @Schema(
      title = "操作人",
      maxLength = 128,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(name = "operator_id", length = 128)
  private String operatorId;

  @Order(11)
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

  @Schema(hidden = true, accessMode = Schema.AccessMode.READ_ONLY)
  @Column(name = "source_service", length = 64)
  private String sourceService;

  @Order(12)
  @Schema(
      title = "变更说明",
      maxLength = 1024,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 1024)
  private String description;
}
