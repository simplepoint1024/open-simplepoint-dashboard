package org.simplepoint.plugin.personal.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;

/** User and workspace ownership is assigned by the service, never from an HTTP body. */
@Data
@MappedSuperclass
@EqualsAndHashCode(callSuper = true)
public abstract class OwnedRecord extends BaseEntityImpl<String> {
  @Column(nullable = false, length = 64)
  private String ownerId;

  @Column(nullable = false, length = 160)
  private String workspace;

  @Version private Long version;
}
