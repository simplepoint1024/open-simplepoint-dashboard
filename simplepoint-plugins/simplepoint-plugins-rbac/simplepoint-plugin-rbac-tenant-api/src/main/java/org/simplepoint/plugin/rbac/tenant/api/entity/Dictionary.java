package org.simplepoint.plugin.rbac.tenant.api.entity;

import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.core.constants.Icons;
import org.simplepoint.core.constants.PublicButtonKeys;
import org.springframework.core.annotation.Order;

/**
 * Represents a configurable dictionary used to drive dynamic enum options.
 */
@Data
@Entity
@Table(name = "simpoint_saas_dictionaries")
@EqualsAndHashCode(callSuper = true)
@ButtonDeclarations({
    @ButtonDeclaration(
        title = PublicButtonKeys.ADD_TITLE,
        key = PublicButtonKeys.ADD_KEY,
        icon = Icons.PLUS_CIRCLE,
        sort = 0,
        argumentMaxSize = 1,
        argumentMinSize = 0,
        authority = "dictionaries.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "dictionaries.edit"
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
        authority = "dictionaries.delete"
    ),
    @ButtonDeclaration(
        title = "i18n:dictionaries.button.config.item",
        key = "config.item",
        color = "orange",
        icon = Icons.SAFETY_OUTLINED,
        sort = 3,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "dictionaries.config.item"
    )
})
@NoArgsConstructor
@AllArgsConstructor
@Tag(name = "字典对象", description = "用于管理系统中的动态字典")
@Schema(title = "字典对象", description = "用于管理系统中的动态字典")
public class Dictionary extends BaseEntityImpl<String> {

  @Order(0)
  @Schema(
      title = "i18n:dictionaries.title.name",
      description = "i18n:dictionaries.description.name",
      example = "功能类型",
      maxLength = 128,
      minLength = 1,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  @Column(length = 128, nullable = false)
  private String name;

  @Order(1)
  @Schema(
      title = "i18n:dictionaries.title.code",
      description = "i18n:dictionaries.description.code",
      example = "feature.type",
      maxLength = 128,
      minLength = 1,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  @Column(length = 128, nullable = false, unique = true)
  private String code;

  @Order(2)
  @Schema(
      title = "i18n:dictionaries.title.description",
      description = "i18n:dictionaries.description.description",
      example = "定义系统中的功能类型枚举",
      maxLength = 512,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  @Column(length = 512)
  private String description;

  @Order(3)
  @Schema(
      title = "i18n:dictionaries.title.sort",
      description = "i18n:dictionaries.description.sort",
      example = "0",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private Integer sort;

  @Order(4)
  @Schema(
      title = "i18n:dictionaries.title.enabled",
      description = "i18n:dictionaries.description.enabled",
      example = "true",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private Boolean enabled;
}
