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
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.api.security.base.BaseUserRoleRelevance;
import org.simplepoint.core.base.entity.impl.TenantBaseEntityImpl;

/**
 * Represents the relationship between users and roles
 * in the RBAC (Role-Based Access Control) system.
 * This entity is mapped to the `rbac_user_role_rel` table and defines the association
 * between usernames and authorities, which represent roles in the system.
 */
@Data
@Entity
@Table(name = "auth_user_role_rel")
@EqualsAndHashCode(callSuper = true)
@Schema(title = "用户角色关联对象", description = "用于定义用户与角色之间的关联关系")
public class UserRoleRelevance extends TenantBaseEntityImpl<String> implements BaseUserRoleRelevance {

  /**
   * The username of the user associated with the role.
   * This field specifies the user's unique identifier in the system.
   */
  @Schema(title = "用户名", description = "关联的用户名")
  private String userId;

  /**
   * The authority associated with the user.
   * This field represents the role or permission assigned to the user.
   */
  @Schema(title = "角色权限", description = "关联的角色权限标识")
  private String roleId;
}
