package org.simplepoint.plugin.i18n.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.core.annotation.FormPropsSchema;
import org.simplepoint.core.annotation.FormSchema;
import org.simplepoint.core.annotation.GenericsType;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;

/**
 * Represents a time zone entity with display name, code, UTC offset, etc.
 */
@Data
@Entity
@EqualsAndHashCode(callSuper = false)
@FormSchema(genericsTypes = {
    @GenericsType(name = "id", value = String.class),
})
@Table(name = "i18n_time_zones", indexes = {
    @Index(name = "idx_i18n_timezones_code", columnList = "code"),
})
public class TimeZone extends BaseEntityImpl<String> {

  /**
   * Display name of the time zone.
   */
  @FormPropsSchema(sort = 1)
  @Column(nullable = false, unique = true)
  private String displayName;

  /**
   * Time zone code (e.g., "Asia/Shanghai").
   */
  @FormPropsSchema(sort = 2)
  @Column(nullable = false, unique = true)
  private String code;

  /**
   * UTC offset in minutes.
   */
  @FormPropsSchema(sort = 3)
  private Integer utcOffset;

  /**
   * Sort order.
   */
  @FormPropsSchema(sort = 4)
  private Integer sort;

  /**
   * Country code to which the time zone belongs.
   */
  @FormPropsSchema(sort = 5)
  private String countryCode;

  /**
   * Description or remarks.
   */
  @FormPropsSchema(sort = 6)
  private String description;
}