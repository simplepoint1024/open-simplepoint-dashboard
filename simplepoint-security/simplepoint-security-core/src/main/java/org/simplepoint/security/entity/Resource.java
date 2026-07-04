/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.security.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.core.constants.Icons;
import org.simplepoint.core.constants.PublicButtonKeys;
import org.springframework.core.annotation.Order;

/**
 * Unified resource entity.
 *
 * <p>A resource is the single authorization unit used for navigation, product capability,
 * page actions, and API access. Route-specific and API-specific columns are optional and
 * are populated only for resource types that need them.</p>
 */
@Data
@Entity
@JsonInclude(JsonInclude.Include.NON_NULL)
@Table(name = "simpoint_ac_resources")
@EqualsAndHashCode(callSuper = true)
@ButtonDeclarations({
    @ButtonDeclaration(
        title = PublicButtonKeys.ADD_TITLE,
        key = PublicButtonKeys.ADD_KEY,
        icon = Icons.PLUS_CIRCLE,
        sort = 0,
        argumentMaxSize = 1,
        argumentMinSize = 0,
        authority = "resources.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "resources.edit"
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
        authority = "resources.delete"
    )
})
@Schema(name = "资源对象", description = "统一描述菜单、页面、功能、动作和接口资源")
public class Resource extends BaseEntityImpl<String> {

  public static final String CODE_FIELD = "code";

  @Order(0)
  @Schema(
      title = "i18n:resources.title.code",
      description = "i18n:resources.description.code",
      maxLength = 120,
      minLength = 1,
      example = "users.view",
      extensions = @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true")
      })
  )
  @Column(length = 120, nullable = false, unique = true)
  private String code;

  @Order(1)
  @Schema(
      title = "i18n:resources.title.name",
      description = "i18n:resources.description.name",
      maxLength = 100,
      minLength = 1,
      example = "用户管理",
      extensions = @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true")
      })
  )
  @Column(length = 100, nullable = false)
  private String name;

  @Schema(title = "i18n:resources.title.title", description = "i18n:resources.description.title")
  @Column(length = 120)
  private String title;

  @Schema(title = "i18n:resources.title.label", description = "i18n:resources.description.label")
  @Column(length = 120)
  private String label;

  @Order(2)
  @Schema(
      title = "i18n:resources.title.type",
      description = "i18n:resources.description.type",
      extensions = @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true")
      })
  )
  @Enumerated(EnumType.STRING)
  @Column(length = 32, nullable = false)
  private ResourceType type;

  @Schema(title = "i18n:resources.title.parentId", description = "i18n:resources.description.parentId", hidden = true)
  @Column(length = 36)
  private String parentId;

  @Schema(title = "i18n:resources.title.pluginId", description = "i18n:resources.description.pluginId")
  @Column(length = 128)
  private String pluginId;

  @Order(3)
  @Schema(
      title = "i18n:resources.title.path",
      description = "i18n:resources.description.path",
      maxLength = 200,
      extensions = @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true")
      })
  )
  @Column(length = 200, unique = true)
  private String path;

  @Order(4)
  @Schema(
      title = "i18n:resources.title.component",
      description = "i18n:resources.description.component",
      maxLength = 160,
      extensions = @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true")
      })
  )
  @Column(length = 160)
  private String component;

  @Order(5)
  @Schema(
      title = "i18n:resources.title.icon",
      description = "i18n:resources.description.icon",
      maxLength = 100,
      extensions = @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
          @ExtensionProperty(name = "widget", value = "IconPicker")
      })
  )
  @Column(length = 100)
  private String icon;

  @Order(6)
  @Schema(
      title = "i18n:resources.title.sort",
      description = "i18n:resources.description.sort",
      extensions = @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true")
      })
  )
  private Integer sort;

  @Schema(title = "i18n:resources.title.routeKind", description = "i18n:resources.description.routeKind")
  @Column(length = 32)
  private String routeKind;

  @Schema(title = "i18n:resources.title.method", description = "i18n:resources.description.method")
  @Column(length = 16)
  private String method;

  @Schema(title = "i18n:resources.title.pattern", description = "i18n:resources.description.pattern")
  @Column(length = 240)
  private String pattern;

  @Schema(title = "i18n:resources.title.description", description = "i18n:resources.description.description")
  @Column(length = 255)
  private String description;

  @Schema(title = "i18n:resources.title.publicAccess", description = "i18n:resources.description.publicAccess")
  @Column(nullable = false)
  private Boolean publicAccess;

  @Schema(title = "i18n:resources.title.requireOrgTenant", description = "i18n:resources.description.requireOrgTenant")
  @Column(nullable = false)
  private Boolean requireOrgTenant;

  @Schema(title = "i18n:resources.title.grantable", description = "i18n:resources.description.grantable")
  @Column(nullable = false)
  private Boolean grantable;

  @Schema(title = "i18n:resources.title.disabled", description = "i18n:resources.description.disabled")
  @Column(nullable = false)
  private Boolean disabled;

  @Schema(title = "i18n:resources.title.danger", description = "i18n:resources.description.danger")
  private Boolean danger;

  @Transient
  private Boolean checked;

  @Transient
  private Boolean partial;

  @Override
  public void prePersist() {
    if ((code == null || code.isBlank()) && path != null && !path.isBlank()) {
      code = path.toLowerCase().replace("/", ".");
    }
    if ((name == null || name.isBlank())) {
      if (label != null && !label.isBlank()) {
        name = label;
      } else if (title != null && !title.isBlank()) {
        name = title;
      } else {
        name = code;
      }
    }
    if (type == null) {
      type = ResourceType.ACTION;
    }
    if (publicAccess == null) {
      publicAccess = false;
    }
    if (requireOrgTenant == null) {
      requireOrgTenant = false;
    }
    if (grantable == null) {
      grantable = type != ResourceType.GROUP && type != ResourceType.MODULE;
    }
    if (disabled == null) {
      disabled = false;
    }
    super.prePersist();
  }
}
