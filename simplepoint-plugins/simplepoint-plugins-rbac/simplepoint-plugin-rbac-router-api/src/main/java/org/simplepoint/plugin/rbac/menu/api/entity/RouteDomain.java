package org.simplepoint.plugin.rbac.menu.api.entity;


import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
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
 * Represents a route domain entity in the RBAC (Role-Based Access Control) system.
 *
 * <p>This class serves as a placeholder for route domain attributes and is mapped to the
 * corresponding database table.</p>
 *
 * @author JinxuLiu
 * @since 1.0
 */
@Data
@Entity
@Table(name = "auth_route_domains")
@EqualsAndHashCode(callSuper = true)
@ButtonDeclarations({
    @ButtonDeclaration(
        title = PublicButtonKeys.ADD_TITLE,
        key = PublicButtonKeys.ADD_KEY,
        icon = Icons.PLUS_CIRCLE,
        sort = 0,
        argumentMaxSize = 1,
        argumentMinSize = 0,
        authority = "route.domain.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "route.domain.edit"
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
        authority = "route.domain.delete"
    )
})
@Schema(name = "路由域对象", description = "用于管理系统中的路由域信息")
public class RouteDomain extends BaseEntityImpl<String> {
  @Order(1)
  @Column(nullable = false, length = 100)
  @Schema(
      title = "i18n:route.domain.title.message",
      description = "i18n:route.domain.description.message",
      maxLength = 100,
      minLength = 1,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private String domainName;

  @Order(2)
  @Column(nullable = false, length = 100)
  @Schema(
      title = "i18n:route.domain.title.displayName",
      description = "i18n:route.domain.description.displayName",
      maxLength = 100,
      minLength = 1,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private String displayName;

  @Order(3)
  @Column(unique = true)
  @Schema(
      title = "i18n:route.domain.title.moduleName",
      description = "i18n:route.domain.description.moduleName",
      maxLength = 255,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  private String moduleName;

  @Column(length = 100)
  @Schema(
      title = "i18n:route.domain.title.region",
      description = "i18n:route.domain.description.region",
      maxLength = 100
  )
  private String region;

  @Lob
  @Order(4)
  @Column(length = 500)
  @Schema(
      title = "i18n:route.domain.title.description",
      description = "i18n:route.domain.description.description",
      maxLength = 500,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "false")
          })
      }
  )
  private String description;
}
