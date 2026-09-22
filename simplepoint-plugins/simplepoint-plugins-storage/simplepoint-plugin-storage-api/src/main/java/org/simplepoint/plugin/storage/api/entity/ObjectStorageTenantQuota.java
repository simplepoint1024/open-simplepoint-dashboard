/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.api.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.springframework.core.annotation.Order;

/** Tenant quota configuration for object storage. */
@Data
@Entity
@Table(name = "simpoint_storage_tenant_quotas")
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Tag(name = "对象存储租户配额", description = "用于限制租户对象存储容量")
@Schema(title = "对象存储租户配额", description = "用于限制租户对象存储容量")
@org.simplepoint.core.query.TableQuery({
  @org.simplepoint.core.query.QueryField("tenantId"),
  @org.simplepoint.core.query.QueryField("tenantName"),
  @org.simplepoint.core.query.QueryField("quotaBytes"),
  @org.simplepoint.core.query.QueryField("usedBytes"),
  @org.simplepoint.core.query.QueryField("remainingBytes"),
  @org.simplepoint.core.query.QueryField("enabled"),
  @org.simplepoint.core.query.QueryField("description"),
  @org.simplepoint.core.query.QueryField("createdAt"),
  @org.simplepoint.core.query.QueryField("updatedAt")
})
public class ObjectStorageTenantQuota extends BaseEntityImpl<String> {

  @Order(0)
  @Schema(title = "i18n:storage.quota.tenantId", description = "i18n:storage.quota.tenantId.desc")
  @Column(name = "tenant_id", length = 64, nullable = false)
  private String tenantId;

  @Order(1)
  @Schema(
      title = "i18n:storage.quota.quotaBytes",
      description = "i18n:storage.quota.quotaBytes.desc")
  @Column(name = "quota_bytes")
  private Long quotaBytes;

  @Order(2)
  @Schema(title = "i18n:storage.quota.enabled", description = "i18n:storage.quota.enabled.desc")
  @Column(name = "enabled")
  private Boolean enabled;

  @Order(3)
  @Schema(
      title = "i18n:storage.quota.description",
      description = "i18n:storage.quota.description.desc")
  @Column(length = 512)
  private String description;

  @Transient
  @Schema(title = "i18n:storage.quota.tenantId", accessMode = Schema.AccessMode.READ_ONLY)
  @org.simplepoint.core.annotation.PermissionFields(
      resourceName = "org.simplepoint.plugin.rbac.tenant.api.entity.Tenant",
      value = "name")
  private String tenantName;

  @Transient
  @Schema(title = "i18n:storage.quota.used", accessMode = Schema.AccessMode.READ_ONLY)
  @org.simplepoint.core.annotation.PermissionFields(
      resource = ObjectStorageObject.class,
      value = "contentLength")
  private Long usedBytes;

  @Transient
  @Schema(title = "i18n:storage.quota.remaining", accessMode = Schema.AccessMode.READ_ONLY)
  @org.simplepoint.core.annotation.PermissionFields(
      resource = ObjectStorageTenantQuota.class,
      value = {"quotaBytes", "usedBytes"})
  private Long remainingBytes;
}
