/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.security.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.api.security.base.BaseRole;
import org.simplepoint.core.annotation.FormSchema;
import org.simplepoint.core.annotation.GenericsType;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;

/**
 * Represents the Role entity in the RBAC (Role-Based Access Control) system.
 * This entity is mapped to the `rbac_roles` table and defines attributes such as
 * role name, authority, description, and priority to manage access control in the system.
 */
@Data
@Entity
@Table(name = "security_roles", indexes = {
    @Index(name = "idx_role_name", columnList = "role_name"),
    @Index(name = "idx_authority", columnList = "authority")
})
@EqualsAndHashCode(callSuper = true)
@FormSchema(genericsTypes = {
    @GenericsType(name = "id", value = String.class),
})
@Schema(title = "角色对象", description = "用于定义系统中的角色及其权限")
public class Role extends BaseEntityImpl<String> implements BaseRole {

  /**
   * The name of the role.
   * This field defines the name identifier for the role within the RBAC system.
   */
  @Schema(title = "角色名称", description = "定义角色的名称")
  private String roleName;

  /**
   * The authority associated with the role.
   * This field specifies the permissions or scope tied to the role.
   */
  @Schema(title = "角色权限", description = "定义角色的权限标识")
  private String authority;

  /**
   * A brief description of the role.
   * Provides additional context or details about the role's purpose and usage.
   */
  @Schema(title = "角色描述", description = "对角色的简要描述")
  private String description;

  /**
   * The priority level of the role.
   * Defines the order or importance of the role in the system.
   * Roles with higher priority levels may override roles with lower levels.
   */
  @Schema(title = "角色优先级", description = "定义角色的优先级，数值越大优先级越高")
  private Integer priority;
}
