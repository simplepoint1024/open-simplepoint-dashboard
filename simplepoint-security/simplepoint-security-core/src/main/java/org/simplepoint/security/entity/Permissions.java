/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.security.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.api.security.base.BasePermissions;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;

/**
 * Represents the Permissions entity in the RBAC (Role-Based Access Control) system.
 * This entity is mapped to the `rbac_permissions` table and defines access control
 * attributes such as permission name, authority, and description.
 */
@Data
@Entity
@EqualsAndHashCode(callSuper = true)
@Table(name = "security_permissions", indexes = {
    @Index(name = "idx_access_permissions_authority", columnList = "authority"),
    @Index(name = "idx_access_permissions_permission_name", columnList = "permissionName")
})
@Schema(title = "权限实体", description = "表示RBAC系统中的权限实体")
public class Permissions extends BaseEntityImpl<String> implements BasePermissions {

  @Column(length = 100, nullable = false)
  @Schema(title = "权限标识", description = "定义权限的标识")
  private String authority;

  @Column(length = 32, nullable = false)
  @Schema(title = "权限类型", description = "权限类型 菜单(menu), 按钮(button), 字段(field) 等")
  private String resourceType;

  @Column(length = 100, nullable = false)
  @Schema(title = "资源", description = "对应资源的id 可能是菜单ID，按钮ID，字段名称等")
  private String resource;

  @Column(length = 100, nullable = false)
  @Schema(title = "请求方法", description = "HTTP请求方法 GET, POST, PUT, DELETE 等")
  private String method;
}
