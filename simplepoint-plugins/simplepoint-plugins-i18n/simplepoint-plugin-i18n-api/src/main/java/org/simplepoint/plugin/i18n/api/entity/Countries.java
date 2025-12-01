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
 * Represents a country with various attributes.
 */
@Data
@Entity
@Table(name = "i18n_countries")
@EqualsAndHashCode(callSuper = true)
@ButtonDeclarations({
    @ButtonDeclaration(
        title = PublicButtonKeys.ADD_TITLE,
        key = PublicButtonKeys.ADD_KEY,
        icon = Icons.PLUS_CIRCLE,
        sort = 0,
        argumentMaxSize = 1,
        argumentMinSize = 0,
        authority = "countries.add"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "countries.edit"
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
        authority = "countries.delete"
    )
})
@NoArgsConstructor
@AllArgsConstructor
@Tag(name = "国家对象", description = "用于管理系统中的国家")
public class Countries extends BaseEntityImpl<String> {

  /**
   * The ISO 3166-1 alpha-2 code of the country.
   */
  @Schema(
      title = "i18n:countries.title.isoCode2",
      description = "i18n:countries.description.isoCode2",
      example = "US",
      extensions = {
          @Extension(name = "x-ui",
              properties = {
                  @ExtensionProperty(name = "x-list-visible", value = "true"),
              })
      })
  @Column(length = 32, nullable = false, unique = true)
  private String isoCode2;

  /**
   * The ISO 3166-1 alpha-3 code of the country.
   */
  @Schema(
      title = "i18n:countries.title.isoCode3",
      description = "i18n:countries.description.isoCode3",
      example = "USA",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  @Column(length = 32, nullable = false, unique = true)
  private String isoCode3;

  /**
   * The numeric code of the country.
   */
  @Schema(
      title = "i18n:countries.title.numericCode",
      description = "i18n:countries.description.numericCode",
      example = "840",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  @Column(length = 32, nullable = false, unique = true)
  private Integer numericCode;

  /**
   * The English name of the country.
   */
  @Schema(
      title = "i18n:countries.title.nameEnglish",
      description = "i18n:countries.description.nameEnglish",
      example = "United States",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      })
  @Column(length = 32, nullable = false, unique = true)
  private String nameEnglish;

  /**
   * The native name of the country.
   */
  @Schema(
      title = "i18n:countries.title.nameNative",
      description = "i18n:countries.description.nameNative",
      example = "United States",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      })
  @Column(length = 32, nullable = false, unique = true)
  private String nameNative;

  /**
   * The currency code of the country.
   */
  @Schema(
      title = "i18n:countries.title.currencyCode",
      description = "i18n:countries.description.currencyCode",
      example = "USD",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private String currencyCode;

  @Schema(
      title = "i18n:countries.title.continent",
      description = "i18n:countries.description.continent",
      example = "North America",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private String continent;

  @Schema(
      title = "i18n:countries.title.subRegion",
      description = "i18n:countries.description.subRegion",
      example = "Northern America",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private String subRegion;

  /**
   * The currency name of the country.
   */
  @Schema(
      title = "i18n:countries.title.currencyName",
      description = "i18n:countries.description.currencyName",
      example = "United States Dollar",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      })
  private String currencyName;

  /**
   * The currency symbol of the country.
   */
  @Schema(
      title = "i18n:countries.title.currencySymbol",
      description = "i18n:countries.description.currencySymbol",
      example = "$",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      })
  private String currencySymbol;

  @Schema(
      title = "i18n:countries.title.currencyNumeric",
      description = "i18n:countries.description.currencyNumeric",
      example = "840",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private String currencyNumeric;

  /**
   * The default timezone of the country.
   */
  @Schema(
      title = "i18n:countries.title.timezone",
      description = "i18n:countries.description.timezone",
      example = "America/New_York",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      })
  private String defaultTimezone;

  /**
   * The phone code of the country.
   */
  @Schema(
      title = "i18n:countries.title.phoneCode",
      description = "i18n:countries.description.phoneCode",
      example = "+1",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private String phoneCode;

  /**
   * The flag icon URL of the country.
   */
  @Schema(
      title = "i18n:countries.title.flagIcon",
      description = "i18n:countries.description.flagIcon",
      example = "国旗图标地址",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private String flagIcon;

  /**
   * Indicates whether the country is enabled.
   */
  @Schema(
      title = "i18n:countries.title.enabled",
      description = "i18n:countries.description.enabled",
      example = "true",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      })
  @Column(nullable = false, unique = true)
  private Boolean enabled;

  /**
   * Pre-insert lifecycle callback to set default values.
   * Ensures that the 'enabled' field is set to true if it is null before persisting.
   */
  @PrePersist
  public void preInsert() {
    if (this.enabled == null) {
      this.enabled = Boolean.TRUE;
    }
  }

}