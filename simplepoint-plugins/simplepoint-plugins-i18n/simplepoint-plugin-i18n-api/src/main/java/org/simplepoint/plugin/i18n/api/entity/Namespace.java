package org.simplepoint.plugin.i18n.api.entity;


import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.simplepoint.core.base.entity.impl.TenantBaseEntityImpl;
import org.simplepoint.core.constants.Icons;
import org.simplepoint.core.constants.PublicButtonKeys;
import org.springframework.core.annotation.Order;

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
        authority = "namespaces.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "namespaces.edit"
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
        authority = "namespaces.delete"
    )
})
@NoArgsConstructor
@Tag(name = "命名空间对象", description = "用于管理系统中的命名空间")
public class Namespace extends TenantBaseEntityImpl<String> {
  /**
   * The name of the namespace.
   */
  @Order(0)
  @Schema(
      title = "i18n:namespaces.title.name",
      description = "i18n:namespaces.description.name",
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
  @Order(1)
  @Schema(
      title = "i18n:namespaces.title.code",
      description = "i18n:namespaces.description.code",
      example = "US",
      extensions = {
          @Extension(name = "x-ui",
              properties = {
                  @ExtensionProperty(name = "x-list-visible", value = "true"),
              })
      })
  private String code;

  /**
   * The module associated with the namespace.
   */
  @Schema(
      title = "i18n:namespaces.title.module",
      description = "i18n:namespaces.description.module",
      example = "core",
      extensions = {
          @Extension(name = "x-ui",
              properties = {
                  @ExtensionProperty(name = "x-list-visible", value = "true"),
              })
      }
  )
  @Order(2)
  private String module;

  /**
   * The description of the namespace.
   */
  @Order(3)
  @Schema(
      title = "i18n:namespaces.title.description",
      description = "i18n:namespaces.description.description",
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
   * Constructor for Namespace.
   *
   * @param name the name of the namespace
   * @param code the code of the namespace
   */
  public Namespace(String name, String code) {
    this.name = name;
    this.code = code;
  }
}
