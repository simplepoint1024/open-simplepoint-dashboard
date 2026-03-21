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
 * Represents an application entity under a tenant.
 */
@Data
@Entity
@Table(
    name = "simpoint_saas_applications"
)
@EqualsAndHashCode(callSuper = true)
@ButtonDeclarations({
    @ButtonDeclaration(
        title = PublicButtonKeys.ADD_TITLE,
        key = PublicButtonKeys.ADD_KEY,
        icon = Icons.PLUS_CIRCLE,
        sort = 0,
        argumentMaxSize = 1,
        argumentMinSize = 0,
        authority = "applications.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "applications.edit"
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
        authority = "applications.delete"
    ),
    @ButtonDeclaration(
        title = "配置功能",
        key = "config.feature",
        color = "orange",
        icon = Icons.SAFETY_OUTLINED,
        sort = 3,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "applications.config.feature"
    )
})
@NoArgsConstructor
@AllArgsConstructor
@Tag(name = "应用对象", description = "用于管理租户下的应用")
@Schema(title = "应用对象", description = "用于管理租户下的应用")
public class Application extends BaseEntityImpl<String> {

  /**
   * The display name of the application.
   */
  @Order(0)
  @Schema(
      title = "i18n:applications.title.name",
      description = "i18n:applications.description.name",
      example = "SimplePoint Dashboard",
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
   * Unique code of the application.
   */
  @Order(1)
  @Schema(
      title = "i18n:applications.title.code",
      description = "i18n:applications.description.code",
      example = "dashboard",
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
   * The description of the application.
   */
  @Order(2)
  @Schema(
      title = "i18n:applications.title.description",
      description = "i18n:applications.description.description",
      example = "Tenant dashboard application",
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
   * Application home page or entry URL.
   */
  @Order(3)
  @Schema(
      title = "i18n:applications.title.homepage",
      description = "i18n:applications.description.homepage",
      example = "https://dashboard.example.com",
      maxLength = 512,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "false"),
          })
      }
  )
  @Column(length = 512)
  private String homepage;

  /**
   * Sort order.
   */
  @Order(4)
  @Schema(
      title = "i18n:applications.title.sort",
      description = "i18n:applications.description.sort",
      example = "0",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private Integer sort;

  /**
   * Whether the application is enabled.
   */
  @Order(5)
  @Schema(
      title = "i18n:applications.title.enabled",
      description = "i18n:applications.description.enabled",
      example = "true",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private Boolean enabled;
}
