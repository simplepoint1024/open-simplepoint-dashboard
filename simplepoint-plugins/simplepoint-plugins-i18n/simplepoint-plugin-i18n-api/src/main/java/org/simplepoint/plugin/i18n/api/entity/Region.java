package org.simplepoint.plugin.i18n.api.entity;

import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.simplepoint.core.annotation.FormSchema;
import org.simplepoint.core.annotation.GenericsType;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.core.constants.Icons;
import org.simplepoint.core.constants.PublicButtonKeys;

/**
 * Represents a region with various attributes.
 */
@Data
@Entity
@Table(name = "i18n_regions")
@EqualsAndHashCode(callSuper = true)
@ButtonDeclarations({
    @ButtonDeclaration(
        title = PublicButtonKeys.ADD_TITLE,
        key = PublicButtonKeys.ADD_KEY,
        icon = Icons.PLUS_CIRCLE,
        sort = 0,
        argumentMaxSize = 1,
        argumentMinSize = 0,
        authority = "regions.add"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "regions.edit"
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
        authority = "regions.delete"
    )
})
@NoArgsConstructor
@AllArgsConstructor
@Tag(name = "区域对象", description = "用于管理系统中的区域")
public class Region extends BaseEntityImpl<String> {

  /**
   * The country code of the region.
   */
  @Schema(
      title = "i18n:region.title.countryCode",
      description = "i18n:region.description.countryCode",
      example = "CN",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      })
  @Column(length = 32, nullable = false, unique = true)
  private String countryCode;

  /**
   * The code of the region.
   */
  @Schema(
      title = "i18n:region.title.code",
      description = "i18n:region.description.code",
      example = "BJ",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      })
  private String code;

  /**
   * The English name of the region.
   */
  @Schema(
      title = "i18n:region.title.nameEnglish",
      description = "i18n:region.description.nameEnglish",
      example = "Beijing",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private String nameEnglish;

  /**
   * The native name of the region.
   */
  @Schema(
      title = "i18n:region.title.nameNative",
      description = "i18n:region.description.nameNative",
      example = "北京",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private String nameNative;

  /**
   * The level of the region (e.g., province, city).
   */
  @Schema(
      title = "i18n:region.title.level",
      description = "i18n:region.description.level",
      example = "province",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private String level;

  /**
   * The postal code of the region.
   */
  @Schema(
      title = "i18n:region.title.postalCode",
      description = "i18n:region.description.postalCode",
      example = "110000",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private String postalCode;

  /**
   * The parent region identifier.
   */
  @Schema(
      title = "i18n:region.title.parentCode",
      description = "i18n:region.description.parentCode",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private String parentId;
}