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
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.core.constants.Icons;
import org.simplepoint.core.constants.PublicButtonKeys;
import org.springframework.core.annotation.Order;

/**
 * Represents a menu entity in the RBAC (Role-Based Access Control) system.
 *
 * <p>This class defines various attributes related to a menu, such as title, label,
 * hierarchical structure, and accessibility settings. It is mapped to the {@code rbac_menus}
 * table in the database.</p>
 *
 * @author JinxuLiu
 * @since 1.0
 */
@Data
@Entity
@JsonInclude(JsonInclude.Include.NON_NULL)
@Table(name = "security_menus")
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
    )
})
@Schema(name = "菜单对象", description = "用于表示系统中的菜单项")
public class Menu extends BaseEntityImpl<String> {
  /**
   * Unique identifier for the menu.
   */
  @Schema(title = "i18n:menus.title.authority", description = "i18n:menus.description.authority", maxLength = 36, minLength = 1, hidden = true)
  @Column(unique = true, nullable = false, length = 36)
  private String authority;

  /**
   * Label associated with the menu.
   */
  @Order(0)
  @Schema(title = "i18n:menus.title.label", description = "i18n:menus.description.label", maxLength = 50, minLength = 1, extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  @Column(nullable = false, length = 50)
  private String label;

  @Order(0)
  @Schema(title = "i18n:menus.title.title", description = "i18n:menus.description.title", maxLength = 100, minLength = 1, extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  private String title;

  /**
   * Parent menu identifier, representing hierarchical structure.
   */
  @Schema(title = "i18n:menus.title.parent", description = "i18n:menus.description.parent", maxLength = 36, minLength = 1, hidden = true)
  @Column(length = 36)
  private String parent;

  /**
   * Icon representing the menu visually.
   */
  @Order(1)
  @Schema(title = "i18n:menus.title.icon", description = "i18n:menus.description.icon", maxLength = 100, minLength = 1, extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
          @ExtensionProperty(name = "widget", value = "IconPicker"),
      })
  })
  @Column(length = 100)
  private String icon;

  @Order(1)
  @Schema(title = "i18n:menus.title.sort", description = "i18n:menus.description.sort", extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  private Integer sort;

  /**
   * Path associated with the menu for navigation.
   */
  @Order(3)
  @Schema(title = "i18n:menus.title.path", description = "i18n:menus.description.path", maxLength = 200, minLength = 1, extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  @Column(nullable = false, length = 200, unique = true)
  private String path;

  /**
   * Type of menu item.
   */
  @Order(2)
  @Schema(title = "i18n:menus.title.type", description = "i18n:menus.description.type", maxLength = 32, minLength = 1, extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  @Column(length = 32)
  private String type;

  /**
   * Associated UI component for rendering the menu.
   *
   * <p>用于渲染菜单的UI组件,如果添加iframe:前缀则为外链"
   * </p>
   */
  @Order(4)
  @Schema(title = "i18n:menus.title.component", description = "i18n:menus.description.component", maxLength = 100, minLength = 5, extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  @Column(length = 100)
  private String component;

  /**
   * Flag indicating if the menu represents a potentially dangerous action.
   */
  @Schema(title = "i18n:menus.title.danger", description = "i18n:menus.description.danger")
  @Column
  private Boolean danger;

  /**
   * Flag indicating if the menu item is disabled.
   */
  @Schema(title = "i18n:menus.title.disabled", description = "i18n:menus.description.disabled", extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  @Column(nullable = false)
  private Boolean disabled;

  @PrePersist
  private void preInsert() {
    if (this.authority == null || this.authority.isEmpty()) {
      // Generate authority from path by replacing "/" with ":"
      this.authority = this.path.toLowerCase().replaceAll("/", ":");
    }
    if (this.disabled == null) {
      this.disabled = false;
    }
  }
}
