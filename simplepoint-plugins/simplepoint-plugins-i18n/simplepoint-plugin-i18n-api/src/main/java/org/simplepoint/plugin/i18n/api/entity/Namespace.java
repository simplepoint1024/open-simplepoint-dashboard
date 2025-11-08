package org.simplepoint.plugin.i18n.api.entity;


import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
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

/**
 * Represents a namespace with various attributes.
 */
@Data
@Entity
@Table(name = "i18n_namespaces")
@EqualsAndHashCode(callSuper = true)
@ButtonDeclarations({
    @ButtonDeclaration(
        title = PublicButtonKeys.ADD_TITLE,
        key = PublicButtonKeys.ADD_KEY,
        icon = Icons.PLUS_CIRCLE,
        sort = 0,
        argumentMaxSize = 1,
        argumentMinSize = 0,
        authority = "menu:namespaces:add"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "menu:namespaces:edit"
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
        authority = "menu:namespaces:delete"
    )
})
@NoArgsConstructor
@AllArgsConstructor
@Tag(name = "命名空间对象", description = "用于管理系统中的命名空间")
public class Namespace extends BaseEntityImpl<String> {
  /**
   * The name of the namespace.
   */
  @Schema(
      title = "i18n:namespace.title.name",
      description = "i18n:namespace.description.name",
      example = "United States",
      extensions = {
          @Extension(name = "x-ui",
              properties = {
                  @ExtensionProperty(name = "x-list-visible", value = "true"),
              })
      }
  )
  private String name;

  /**
   * The code of the namespace.
   */
  @Schema(
      title = "i18n:namespace.title.code",
      description = "i18n:namespace.description.code",
      example = "US",
      extensions = {
          @Extension(name = "x-ui",
              properties = {
                  @ExtensionProperty(name = "x-list-visible", value = "true"),
              })
      })
  private String code;

  /**
   * The description of the namespace.
   */
  @Schema(
      title = "i18n:namespace.title.description",
      description = "i18n:namespace.description.description",
      example = "Namespace for United States",
      extensions = {
          @Extension(name = "x-ui",
              properties = {
                  @ExtensionProperty(name = "x-list-visible", value = "true"),
              })
      }
  )
  private String description;

  /**
   * The module associated with the namespace.
   */
  @Schema(
      title = "i18n:namespace.title.module",
      description = "i18n:namespace.description.module",
      example = "core",
      extensions = {
          @Extension(name = "x-ui",
              properties = {
                  @ExtensionProperty(name = "x-list-visible", value = "true"),
              })
      }
  )
  private String module;
}
