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
 * Represents a language with various attributes.
 */
@Data
@Entity
@EqualsAndHashCode(callSuper = false)
@FormSchema(genericsTypes = {
    @GenericsType(name = "id", value = String.class),
})
@Table(name = "i18n_languages", indexes = {
    @Index(name = "idx_i18n_languages_code", columnList = "code"),
})
public class Language extends BaseEntityImpl<String> {

  /**
   * The name of the language.
   */
  @FormPropsSchema(sort = 1)
  @Column(nullable = false, unique = true)
  private String displayName;

  /**
   * The code of the language, typically a two-letter ISO code (e.g., "en", "fr").
   */
  @FormPropsSchema(sort = 2)
  @Column(nullable = false, unique = true)
  private String code;

  /**
   * Indicates whether the language is enabled.
   */
  @FormPropsSchema(sort = 7)
  private Boolean enabled;

  /**
   * The order of the language in a list, used for sorting.
   */
  @FormPropsSchema(sort = 8)
  private Integer sort; // 排序

  /**
   * The flag icon URL or identifier for the language.
   */
  @FormPropsSchema(sort = 9)
  private String flagIcon;

  /**
   * Additional description or notes about the language.
   */
  @FormPropsSchema(sort = 10)
  private String description;
}
