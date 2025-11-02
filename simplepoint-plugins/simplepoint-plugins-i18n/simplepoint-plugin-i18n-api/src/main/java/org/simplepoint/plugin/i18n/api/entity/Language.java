package org.simplepoint.plugin.i18n.api.entity;

import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
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
 * Represents a language with various attributes.
 */
@Data
@Entity
@Table(name = "i18n_languages")
@EqualsAndHashCode(callSuper = true)
@ButtonDeclarations({
    @ButtonDeclaration(
        title = PublicButtonKeys.ADD_TITLE,
        key = PublicButtonKeys.ADD_KEY,
        icon = Icons.PLUS_CIRCLE,
        sort = 0,
        argumentMaxSize = 1,
        argumentMinSize = 0
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.DELETE_TITLE,
        key = PublicButtonKeys.DELETE_KEY,
        color = "danger",
        icon = Icons.MINUS_CIRCLE,
        sort = 2,
        argumentMinSize = 1,
        argumentMaxSize = 10,
        danger = true
    )
})
@NoArgsConstructor
@AllArgsConstructor
@Tag(name = "语言对象", description = "用于管理系统中的语言")
public class Language extends BaseEntityImpl<String> {


  /**
   * The language code ISO 639-1 (e.g., "en", "fr", "zh").
   */
  @Schema(
      title = "i18n:language.title.code",
      description = "i18n:language.description.code",
      example = "CN",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      })
  private String code;

  /**
   * The locale associated with the language (e.g., "zh-CN", "en_US", "fr_FR").
   */
  @Schema(
      title = "i18n:language.title.locale",
      description = "i18n:language.description.locale",
      example = "zh-CN",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private String locale;

  /**
   * The English name of the language (e.g., "Chinese", "French").
   */
  @Schema(
      title = "i18n:language.title.nameEnglish",
      description = "i18n:language.description.nameEnglish",
      example = "Chinese",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private String nameEnglish;

  /**
   * The native name of the language (e.g., "中文" for Chinese, "Français" for French).
   */
  @Schema(
      title = "i18n:language.title.nameNative",
      description = "i18n:language.description.nameNative",
      example = "中文",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private String nameNative;

  /**
   * The text direction of the language (e.g., "LTR" for left-to-right, "RTL" for right-to-left).
   */
  @Schema(
      title = "i18n:language.title.textDirection",
      description = "i18n:language.description.textDirection",
      example = "LTR",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private String textDirection;

  @Schema(
      title = "i18n:language.title.dateFormat",
      description = "i18n:language.description.dateFormat",
      example = "yyyy-MM-dd",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private String dateFormat;

  /**
   * Indicates whether the language is enabled.
   */
  @Schema(
      title = "i18n:language.title.enabled",
      description = "i18n:language.description.enabled",
      example = "true",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  @Column
  private Boolean enabled;

  /**
   * Additional description or notes about the language.
   */
  @Schema(
      title = "i18n:language.title.description",
      description = "i18n:language.description.description",
      example = "This language is used for the Chinese locale.",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  @Column
  private String description;

  /**
   * Pre-insert lifecycle callback to set default values.
   * Ensures that the 'enabled' field is set to true if it is null before persisting.
   */
  @PrePersist
  public void preInsert() {
    if (enabled == null) {
      enabled = true;
    }
  }
}
