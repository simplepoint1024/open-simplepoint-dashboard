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
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.api.security.base.BasePermissions;
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.simplepoint.core.base.entity.impl.TenantBaseEntityImpl;
import org.simplepoint.core.constants.Icons;
import org.simplepoint.core.constants.PublicButtonKeys;

/**
 * Represents the Permissions entity in the RBAC (Role-Based Access Control) system.
 * This entity is mapped to the `rbac_permissions` table and defines access control
 * attributes such as permission name, authority, and description.
 */
@Data
@Entity
@EqualsAndHashCode(callSuper = true)
@Table(name = "auth_permissions")
@ButtonDeclarations({
    @ButtonDeclaration(
        title = PublicButtonKeys.ADD_TITLE,
        key = PublicButtonKeys.ADD_KEY,
        icon = Icons.PLUS_CIRCLE,
        sort = 0,
        argumentMaxSize = 1,
        argumentMinSize = 0,
        authority = "permissions.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "permissions.edit"
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
        authority = "permissions.delete"
    )
})
@Schema(title = "权限实体", description = "表示RBAC系统中的权限实体")
public class Permissions extends TenantBaseEntityImpl<String> implements BasePermissions {
  public static final String AUTHORITY_FIELD = "authority";

  @Schema(
      title = "i18n:permissions.title.name",
      description = "i18n:permissions.description.name",
      example = "查看用户列表",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  @Column(length = 100, nullable = false)
  private String name;

  @Schema(
      title = "i18n:permissions.title.authority",
      description = "i18n:permissions.description.authority",
      example = "ROLE_ADMIN",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  @Column(length = 100, nullable = false, unique = true)
  private String authority;

  @Column(length = 100, nullable = false)
  @Schema(
      title = "i18n:permissions.title.resource",
      description = "i18n:permissions.description.resource",
      example = "/system/menu",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })}
  )
  private String resource;

  @Schema(
      title = "i18n:permissions.title.description",
      description = "i18n:permissions.description.description",
      example = "系统管理员角色，拥有所有权限",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  @Column(length = 100, nullable = false)
  private String description;
}
