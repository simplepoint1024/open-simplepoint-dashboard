/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.security.entity;

import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.api.security.base.BaseRole;
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.core.constants.Icons;
import org.simplepoint.core.constants.PublicButtonKeys;

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
@ButtonDeclarations({
    @ButtonDeclaration(
        title = PublicButtonKeys.ADD_TITLE,
        key = PublicButtonKeys.ADD_KEY,
        icon = Icons.PLUS_CIRCLE,
        sort = 0,
        argumentMaxSize = 1,
        argumentMinSize = 0
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.DELETE_TITLE,
        key = PublicButtonKeys.DELETE_KEY,
        color = "danger",
        icon = Icons.MINUS_CIRCLE,
        sort = 2,
        argumentMinSize = 1,
        argumentMaxSize = 10,
        danger = true
    ),
    @ButtonDeclaration(
        title = "i18n:table.button.permissionConfig",
        key = "permissionConfig",
        color = "orange",
        icon = Icons.SAFETY_OUTLINED,
        sort = 3,
        argumentMinSize = 1,
        argumentMaxSize = 1
    )
})
@Schema(title = "角色对象", description = "用于定义系统中的角色及其权限")
public class Role extends BaseEntityImpl<String> implements BaseRole {

  /**
   * The name of the role.
   * This field defines the name identifier for the role within the RBAC system.
   */
  @Schema(title = "i18n:roles.title.roleName", description = "i18n:roles.description.roleName", maxLength = 50, minLength = 1, extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  private String roleName;

  /**
   * The authority associated with the role.
   * This field specifies the permissions or scope tied to the role.
   */
  @Schema(title = "i18n:roles.title.authority", description = "i18n:roles.description.authority", maxLength = 100, minLength = 1, extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })})
  private String authority;

  /**
   * A brief description of the role.
   * Provides additional context or details about the role's purpose and usage.
   */
  @Schema(title = "i18n:roles.title.description", description = "i18n:roles.description.description", maxLength = 200, extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true")
      })})
  private String description;

  /**
   * The priority level of the role.
   * Defines the order or importance of the role in the system.
   * Roles with higher priority levels may override roles with lower levels.
   */
  @Schema(title = "i18n:roles.title.priority", description = "i18n:roles.description.priority", example = "1", extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })})
  private Integer priority;
}
