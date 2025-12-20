package org.simplepoint.security.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;

/**
 * Resource entity representing a security resource in the system.
 *
 * @since v0.0.2
 */
@Data
@Entity
@EqualsAndHashCode(callSuper = true)
public class Resource extends BaseEntityImpl<String> {
  /**
   * The name of the resource.
   */
  @Column(nullable = false)
  private String resourceName;

  /**
   * The type of the resource.
   */
  @Column(nullable = false)
  private String resourceType;

  /**
   * The parent of the resource.
   */
  @Column(nullable = false)
  private String resourceParent;

  /**
   * The authority string for the resource.
   */
  @Column(unique = true, nullable = false)
  private String resourceAuthority;

  /**
   * A description of the resource.
   */
  private String resourceDescription;
}
