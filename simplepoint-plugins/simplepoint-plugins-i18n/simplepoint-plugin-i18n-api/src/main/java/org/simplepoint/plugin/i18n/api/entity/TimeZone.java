package org.simplepoint.plugin.i18n.api.entity;

import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.springframework.core.annotation.Order;

/**
 * Represents a time zone entity with display name, code, UTC offset, etc.
 */
@Data
@Entity
@Table(name = "i18n_timezones")
@EqualsAndHashCode(callSuper = true)
@ButtonDeclarations({
    @ButtonDeclaration(
        title = PublicButtonKeys.ADD_TITLE,
        key = PublicButtonKeys.ADD_KEY,
        icon = Icons.PLUS_CIRCLE,
        sort = 0,
        argumentMaxSize = 1,
        argumentMinSize = 0,
        authority = "timezones.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "timezones.edit"
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
        authority = "timezones.delete"
    )
})
@NoArgsConstructor
@AllArgsConstructor
@Tag(name = "时区对象", description = "用于管理系统中的时区")
public class TimeZone extends BaseEntityImpl<String> {

  /**
   * The English name of the time zone.
   */
  @Order(0)
  @Schema(
      title = "i18n:timezones.title.nameEnglish",
      description = "i18n:timezones.description.nameEnglish",
      example = "China Standard Time",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      })
  private String nameEnglish;

  /**
   * The native name of the time zone.
   */
  @Order(1)
  @Schema(
      title = "i18n:timezones.title.nameNative",
      description = "i18n:timezones.description.nameNative",
      example = "中国标准时间",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      })
  private String nameNative;

  /**
   * The IANA time zone code (e.g., "Asia/Shanghai").
   */
  @Order(2)
  @Schema(
      title = "i18n:timezones.title.timezoneCode",
      description = "i18n:timezones.description.timezoneCode",
      example = "Asia/Shanghai",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      })
  private String code;

  /**
   * The UTC offset of the time zone (e.g., "+08:00", "-05:00").
   */
  @Order(3)
  @Schema(
      title = "i18n:timezones.title.utcOffset",
      description = "i18n:timezones.description.utcOffset",
      example = "+08:00",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      })
  private String utcOffset;

  /**
   * The country code associated with the time zone.
   */
  @Order(4)
  @Schema(
      title = "i18n:timezones.title.countryCode",
      description = "i18n:timezones.description.countryCode",
      example = "CN",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      })
  private String countryCode;

  /**
   * Indicates whether the time zone observes Daylight Saving Time (DST).
   */
  @Order(5)
  @Schema(
      title = "i18n:timezones.title.isDst",
      description = "i18n:timezones.description.isDst",
      example = "true",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private Boolean isDst;

  /**
   * Indicates whether the time zone is enabled.
   */
  @Order(6)
  @Schema(
      title = "i18n:timezones.title.enabled",
      description = "i18n:timezones.description.enabled",
      example = "true",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private Boolean enabled;


  /**
   * Pre-persist lifecycle callback to set default values.
   */
  @PrePersist
  public void prePersist() {
    if (enabled == null) {
      enabled = true;
    }
  }

}