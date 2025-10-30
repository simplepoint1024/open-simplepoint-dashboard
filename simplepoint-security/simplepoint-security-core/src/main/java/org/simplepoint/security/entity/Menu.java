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
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.simplepoint.core.annotation.FormSchema;
import org.simplepoint.core.annotation.GenericsType;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
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
@Table(name = "security_menus", indexes = {
    @Index(name = "idx_menus_uuid", columnList = "uuid"),
})
@EqualsAndHashCode(callSuper = true)
@FormSchema(genericsTypes = {
    @GenericsType(name = "id", value = String.class),
})
@ButtonDeclarations({
    @ButtonDeclaration(
        title = "添加", key = "add", icon = "PlusCircleOutlined", sort = 0, argumentMaxSize = 1, argumentMinSize = 0
    ),
    @ButtonDeclaration(
        title = "编辑", key = "edit", color = "orange", icon = "EditOutlined", sort = 1,
        argumentMinSize = 1, argumentMaxSize = 1
    ),
    @ButtonDeclaration(
        title = "删除", key = "delete", color = "danger", icon = "MinusCircleOutlined", sort = 2,
        argumentMinSize = 1, argumentMaxSize = 10, danger = true
    )
})
@Schema(name = "菜单对象", description = "用于表示系统中的菜单项")
public class Menu extends BaseEntityImpl<String> {
  /**
   * Unique identifier for the menu.
   */
  @Schema(title = "菜单UUID", description = "菜单的唯一标识符", maxLength = 36, minLength = 1, hidden = true)
  @Column(unique = true, nullable = false, length = 36)
  private String uuid;

  /**
   * Label associated with the menu.
   */
  @Order(0)
  @Schema(title = "菜单标签", description = "菜单的标签", maxLength = 50, minLength = 1, extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  @Column(nullable = false, length = 50)
  private String label;

  /**
   * Parent menu identifier, representing hierarchical structure.
   */
  @Schema(title = "父级菜单", description = "父级菜单的标识符", maxLength = 36, minLength = 1, hidden = true)
  @Column(length = 36)
  private String parent;

  /**
   * Icon representing the menu visually.
   */
  @Order(1)
  @Schema(title = "菜单图标", description = "菜单的图标", maxLength = 100, minLength = 1, extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
          @ExtensionProperty(name = "widget", value = "IconPicker"),
      })
  })
  @Column(length = 100)
  private String icon;

  /**
   * Path associated with the menu for navigation.
   */
  @Order(3)
  @Schema(title = "菜单路径", description = "菜单的导航路径", maxLength = 200, minLength = 1, extensions = {
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
  @Schema(title = "菜单类型", description = "菜单项的类型", maxLength = 32, minLength = 1, extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  @Column(length = 32)
  private String type;

  /**
   * Associated UI component for rendering the menu.
   */
  @Order(4)
  @Schema(title = "菜单组件", description = "用于渲染菜单的UI组件,如果添加iframe:前缀则为外链", maxLength = 100, minLength = 5, extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  @Column(length = 100)
  private String component;

  /**
   * Flag indicating if the menu represents a potentially dangerous action.
   */
  @Schema(title = "危险标志", description = "指示菜单项是否表示潜在的危险操作")
  @Column
  private Boolean danger;

  /**
   * Flag indicating if the menu item is disabled.
   */
  @Schema(title = "禁用标志", description = "指示菜单项是否被禁用", extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  @Column(nullable = false)
  private Boolean disabled;
}
