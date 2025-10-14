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
 * Represents a country with various attributes.
 */
@Data
@Entity
@EqualsAndHashCode(callSuper = false)
@FormSchema(genericsTypes = {
    @GenericsType(name = "id", value = String.class),
})
@Table(name = "i18n_countries", indexes = {
    @Index(name = "idx_i18n_countries_code", columnList = "code"),
})
public class Countries extends BaseEntityImpl<String> {

  /**
   * The display name of the country.
   */
  @FormPropsSchema(sort = 1)
  @Column(nullable = false, unique = true)
  private String displayName;

  /**
   * The code of the country.
   */
  @FormPropsSchema(sort = 2)
  @Column(nullable = false, unique = true)
  private String code;

  /**
   * The order of the country in a list, used for sorting.
   */
  @FormPropsSchema(sort = 3)
  private Integer sort;

  /**
   * The range code of the country.
   */
  @FormPropsSchema(sort = 4)
  private String rangeCode;

  /**
   * Additional description or notes about the country.
   */
  @FormPropsSchema(sort = 5)
  private String description;
}