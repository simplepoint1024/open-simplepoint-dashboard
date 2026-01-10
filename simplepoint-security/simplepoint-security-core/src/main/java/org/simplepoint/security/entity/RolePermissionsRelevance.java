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
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.api.security.base.BaseRolePermissionsRelevance;
import org.simplepoint.core.base.entity.impl.TenantBaseEntityImpl;

/**
 * Represents the relationship between roles and permissions in the
 * RBAC (Role-Based Access Control) system.
 * This entity is mapped to the `auth_permissions_role_rel`
 * table and defines the association between
 * role authorities and permission authorities.
 */
@Data
@Entity
@EqualsAndHashCode(callSuper = true)
@Table(name = "auth_permissions_role_rel")
@Schema(title = "角色权限关联实体", description = "表示RBAC系统中角色与权限之间的关联关系")
public class RolePermissionsRelevance extends TenantBaseEntityImpl<String> implements BaseRolePermissionsRelevance {

  /**
   * The authority of the role associated with the relationship.
   * This field specifies the unique identifier or scope of the role.
   */
  @Column(nullable = false)
  @Schema(title = "角色标识", description = "与角色关联的标识，通常用于定义角色的范围或权限")
  @JoinColumn(name = "role_id", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_role_permission_role"))
  private String roleId;

  /**
   * The authority of the permission associated with the relationship.
   * This field specifies the unique identifier or scope of the permission.
   */
  @Column(nullable = false)
  @Schema(title = "权限标识", description = "与权限关联的标识，通常用于定义权限的范围或角色")
  @JoinColumn(name = "permission_id", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_role_permission_permission"))
  private String permissionId;

  @Schema(title = "数据权限标识", description = "与数据权限关联的标识，通常用于定义数据权限的范围或角色")
  private String dataScopeId;

  @Schema(title = "字段权限标识", description = "与字段权限关联的标识，通常用于定义字段权限的范围或角色")
  private String fieldScopeId;
}
