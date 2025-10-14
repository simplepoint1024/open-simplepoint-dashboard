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
 * Represents a region with various attributes.
 */
@Data
@Entity
@EqualsAndHashCode(callSuper = false)
@FormSchema(genericsTypes = {
    @GenericsType(name = "id", value = String.class),
})
@Table(name = "i18n_regions", indexes = {
    @Index(name = "idx_i18n_regions_code", columnList = "code"),
})
public class Region extends BaseEntityImpl<String> {

  /**
   * The code of the region.
   */
  @FormPropsSchema(sort = 1)
  @Column(nullable = false, unique = true)
  private String code;

  /**
   * The display name of the region.
   */
  @FormPropsSchema(sort = 2)
  @Column(nullable = false, unique = true)
  private String displayName;

  /**
   * The order of the region in a list, used for sorting.
   */
  @FormPropsSchema(sort = 3)
  private Integer sort;

  /**
   * Additional description or notes about the region.
   */
  @FormPropsSchema(sort = 4)
  private String description;
}