package org.simplepoint.security.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
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
import org.simplepoint.core.base.entity.impl.TenantBaseEntityImpl;
import org.simplepoint.core.constants.Icons;
import org.simplepoint.core.constants.PublicButtonKeys;

/**
 * Represents a remote module entity in the RBAC (Role-Based Access Control) system.
 *
 * <p>This class defines attributes related to a remote module, such as its name and entry point.
 * It is mapped to the {@code remote_modules} table in the database.</p>
 *
 * @author JinxuLiu
 * @since 1.0
 */
@Data
@Entity
@Table(name = "auth_micro_modules")
@ButtonDeclarations({
    @ButtonDeclaration(
        title = PublicButtonKeys.ADD_TITLE,
        key = PublicButtonKeys.ADD_KEY,
        icon = Icons.PLUS_CIRCLE,
        sort = 0,
        argumentMaxSize = 1,
        argumentMinSize = 0,
        authority = "micro.module.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "micro.module.edit"
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
        authority = "micro.module.delete"
    )
})
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(title = "微模块对象", description = "用于加载微前端远程模块")
public class MicroModule extends TenantBaseEntityImpl<String> {

  @Column(nullable = false)
  @Schema(title = "i18n:micro.module.title.displayName",
      description = "i18n:route.domain.description.displayName",
      maxLength = 255,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      })
  private String displayName;

  @Schema(title = "i18n:micro.module.title.serviceName",
      description = "i18n:micro.domain.description.serviceName",
      maxLength = 255,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      })
  private String serviceName;

  @Column(nullable = false, unique = true)
  @Schema(title = "i18n:micro.module.title.entry",
      description = "i18n:micro.domain.description.entry",
      maxLength = 255,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      })
  private String entry;

  @Lob
  @Schema(title = "i18n:micro.module.title.description",
      description = "i18n:micro.domain.description.description",
      maxLength = 255,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      })
  private String description;
}
