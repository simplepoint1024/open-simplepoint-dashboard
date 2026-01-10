package org.simplepoint.core.entity;

import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.simplepoint.core.base.entity.impl.TenantBaseEntityImpl;
import org.simplepoint.core.constants.Icons;
import org.simplepoint.core.constants.PublicButtonKeys;
import org.springframework.core.annotation.Order;

/**
 * Represents an internationalization (i18n) message key-value pair in the system.
 *
 * <p>This class defines the structure for storing i18n messages, which are used
 * for supporting multiple languages in the application. It is mapped to the
 * {@code i18n_messages} table in the database.</p>
 *
 * @author JinxuLiu
 * @since 1.0
 */
@Data
@Entity
@Table(name = "i18n_messages", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"namespace", "code", "locale"})
})
@EqualsAndHashCode(callSuper = true)
@ButtonDeclarations({
    @ButtonDeclaration(
        title = PublicButtonKeys.ADD_TITLE,
        key = PublicButtonKeys.ADD_KEY,
        icon = Icons.PLUS_CIRCLE,
        sort = 0,
        argumentMaxSize = 1,
        argumentMinSize = 0,
        authority = "messages.create"),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "messages.edit"),
    @ButtonDeclaration(
        title = PublicButtonKeys.DELETE_TITLE,
        key = PublicButtonKeys.DELETE_KEY,
        color = "danger",
        icon = Icons.MINUS_CIRCLE,
        sort = 2,
        argumentMinSize = 1,
        argumentMaxSize = 10,
        danger = true,
        authority = "messages.delete"
    )
})
@Schema(name = "I18n键值对象", description = "用于表示系统中的国际化键值项")
public class Message extends TenantBaseEntityImpl<String> {

  @Order(0)
  @Column(nullable = false, length = 128)
  @Schema(title = "i18n:messages.title.locale", description = "i18n:messages.description.locale", maxLength = 128, minLength = 1, extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  private String locale;

  @Order(1)
  @Column(nullable = false, length = 128)
  @Schema(title = "i18n:messages.title.namespace", description = "i18n:messages.description.namespace", maxLength = 128, minLength = 1, extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  private String namespace;

  @Order(2)
  @Column(nullable = false, length = 256)
  @Schema(title = "i18n:messages.title.code", description = "i18n:messages.description.code", maxLength = 256, minLength = 1, extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  private String code;

  @Order(3)
  @Column(nullable = false, length = 2048)
  @Schema(title = "i18n:messages.title.message", description = "i18n:messages.description.message", maxLength = 2048, minLength = 1, extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  private String message;

  @Order(4)
  @Column(length = 2048)
  @Schema(title = "i18n:messages.title.description", description = "i18n:messages.description.description", maxLength = 2048, minLength = 1)
  private String description;

  @Order(5)
  @Column(nullable = false)
  @Schema(title = "i18n:messages.title.global", description = "i18n:messages.description.global", extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  private Boolean global;
}
