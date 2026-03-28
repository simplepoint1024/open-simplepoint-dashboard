package org.simplepoint.plugin.rbac.tenant.api.entity;

import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
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
 * Represents a package (plan) that can be assigned to a tenant.
 */
@Data
@Entity
@Table(
    name = "simpoint_saas_packages"
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
        authority = "packages.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "packages.edit"
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
        authority = "packages.delete"
    ),
    @ButtonDeclaration(
        title = "i18n:packages.button.config.application",
        key = "config.application",
        color = "orange",
        icon = Icons.SAFETY_OUTLINED,
        sort = 3,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "packages.config.application"
    )
})
@NoArgsConstructor
@AllArgsConstructor
@Tag(name = "套餐包对象", description = "用于管理租户下的套餐包")
@Schema(title = "套餐包对象", description = "用于管理租户下的套餐包")
public class Package extends BaseEntityImpl<String> {

  /**
   * The display name of the package.
   */
  @Order(0)
  @Schema(
      title = "i18n:packages.title.name",
      description = "i18n:packages.description.name",
      example = "Standard",
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
   * Unique code of the package.
   */
  @Order(1)
  @Schema(
      title = "i18n:packages.title.code",
      description = "i18n:packages.description.code",
      example = "standard",
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
   * The description of the package.
   */
  @Order(2)
  @Schema(
      title = "i18n:packages.title.description",
      description = "i18n:packages.description.description",
      example = "Standard package with basic features",
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
   * Price of the package.
   */
  @Order(3)
  @Schema(
      title = "i18n:packages.title.price",
      description = "i18n:packages.description.price",
      example = "99.99",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  @Column(precision = 19, scale = 2)
  private BigDecimal price;

  /**
   * Validity duration in days (null means unlimited).
   */
  @Order(4)
  @Schema(
      title = "i18n:packages.title.durationDays",
      description = "i18n:packages.description.durationDays",
      example = "30",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private Integer durationDays;

  /**
   * Sort order.
   */
  @Order(5)
  @Schema(
      title = "i18n:packages.title.sort",
      description = "i18n:packages.description.sort",
      example = "0",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private Integer sort;

  /**
   * Whether the package is enabled.
   */
  @Order(6)
  @Schema(
      title = "i18n:packages.title.enabled",
      description = "i18n:packages.description.enabled",
      example = "true",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private Boolean enabled;
}
