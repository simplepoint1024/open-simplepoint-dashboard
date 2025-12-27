package org.simplepoint.plugin.rbac.menu.api.entity;


import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.core.constants.Icons;
import org.simplepoint.core.constants.PublicButtonKeys;

/**
 * Represents a microservice entity in the RBAC (Role-Based Access Control) system.
 *
 * <p>This class defines attributes related to a microservice and is mapped to the
 * {@code auth_micro_services} table in the database.</p>
 *
 * @author JinxuLiu
 * @since 1.0
 */
@Data
@Entity
@Table(name = "auth_micro_services")
@EqualsAndHashCode(callSuper = true)
@ButtonDeclarations({
    @ButtonDeclaration(
        title = PublicButtonKeys.ADD_TITLE,
        key = PublicButtonKeys.ADD_KEY,
        icon = Icons.PLUS_CIRCLE,
        sort = 0,
        argumentMaxSize = 1,
        argumentMinSize = 0,
        authority = "micro.service.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "micro.service.edit"
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
        authority = "micro.service.delete"
    )
})
@Schema(name = "微服务对象", description = "用于管理系统中的微服务信息")
public class MicroService extends BaseEntityImpl<String> {

  @Schema(title = "i18n:micro.service.title.displayName",
      description = "i18n:micro.service.description.displayName",
      maxLength = 255,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      })
  private String displayName;

  @Schema(title = "i18n:micro.service.title.serviceName",
      description = "i18n:micro.service.description.serviceName",
      maxLength = 255,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      })
  private String serviceName;

  @Schema(title = "i18n:micro.service.title.lastHeartbeat",
      description = "i18n:micro.service.description.lastHeartbeat",
      maxLength = 255,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      })
  private LocalDateTime lastHeartbeat;

  @Lob
  @Column(length = 2000)
  @Schema(title = "i18n:micro.service.title.description",
      description = "i18n:micro.service.description.description",
      maxLength = 2000,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      })
  private String description;
}
