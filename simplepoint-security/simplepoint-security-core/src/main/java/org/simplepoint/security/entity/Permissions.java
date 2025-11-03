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
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.api.security.base.BasePermissions;
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
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
@Table(name = "security_permissions", indexes = {
    @Index(name = "idx_access_permissions_authority", columnList = "authority"),
    @Index(name = "idx_access_permissions_permission_name", columnList = "permissionName")
})
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
    )
})
@Schema(title = "权限实体", description = "表示RBAC系统中的权限实体")
public class Permissions extends BaseEntityImpl<String> implements BasePermissions {

  @Schema(
      title = "i18n:permissions.title.authority",
      description = "i18n:permissions.description.authority",
      example = "ADMIN",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  @Column(length = 100, nullable = false)
  private String authority;

  @Column(length = 32, nullable = false)
  @Schema(
      title = "i18n:permissions.title.permissionName",
      description = "i18n:permissions.description.permissionName",
      example = "menu,button,field",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private String resourceType;

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

  @Column(length = 100, nullable = false)
  @Schema(
      title = "i18n:permissions.title.method",
      description = "i18n:permissions.description.method",
      example = "GET,POST,PUT,DELETE",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private String method;
}
