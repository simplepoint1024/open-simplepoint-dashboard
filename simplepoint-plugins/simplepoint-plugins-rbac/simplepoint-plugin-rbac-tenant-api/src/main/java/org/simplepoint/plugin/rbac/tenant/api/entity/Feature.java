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
 * Represents a feature entity.
 */
@Data
@Entity
@Table(name = "auth_features")
@EqualsAndHashCode(callSuper = true)
@ButtonDeclarations({
    @ButtonDeclaration(
        title = PublicButtonKeys.ADD_TITLE,
        key = PublicButtonKeys.ADD_KEY,
        icon = Icons.PLUS_CIRCLE,
        sort = 0,
        argumentMaxSize = 1,
        argumentMinSize = 0,
        authority = "features.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "features.edit"
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
        authority = "features.delete"
    )
})
@NoArgsConstructor
@AllArgsConstructor
@Tag(name = "功能对象", description = "用于管理系统中的功能特性")
@Schema(title = "功能对象", description = "用于管理系统中的功能特性")
public class Feature extends BaseEntityImpl<String> {

  /**
   * The display name of the feature.
   */
  @Order(0)
  @Schema(
      title = "i18n:features.title.name",
      description = "i18n:features.description.name",
      example = "RBAC",
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

  /**
   * The description of the feature.
   */
  @Order(1)
  @Schema(
      title = "i18n:features.title.description",
      description = "i18n:features.description.description",
      example = "Role based access control feature",
      maxLength = 512,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  @Column(length = 512)
  private String description;

  /**
   * Unique code of the feature.
   */
  @Order(2)
  @Schema(
      title = "i18n:features.title.code",
      description = "i18n:features.description.code",
      example = "rbac",
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

  /**
   * Parent feature code.
   */
  @Order(3)
  @Schema(
      title = "i18n:features.title.parentCode",
      description = "i18n:features.description.parentCode",
      example = "system",
      maxLength = 128,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  @Column(length = 128)
  private String parentCode;

  /**
   * Sort order of the feature.
   */
  @Order(4)
  @Schema(
      title = "i18n:features.title.sort",
      description = "i18n:features.description.sort",
      example = "0",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private Integer sort;

  /**
   * Type of the feature (e.g. menu/button/api).
   */
  @Order(5)
  @Schema(
      title = "i18n:features.title.type",
      description = "i18n:features.description.type",
      example = "0",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private Integer type;

  /**
   * Ancestor path of the feature.
   */
  @Order(6)
  @Schema(
      title = "i18n:features.title.ancestor",
      description = "i18n:features.description.ancestor",
      example = "system/rbac",
      maxLength = 1024,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "false"),
          })
      }
  )
  @Column(length = 1024)
  private String ancestor;
}
