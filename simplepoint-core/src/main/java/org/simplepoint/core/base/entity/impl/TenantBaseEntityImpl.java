package org.simplepoint.core.base.entity.impl;

import com.fasterxml.jackson.databind.annotation.JacksonStdImpl;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.simplepoint.api.base.TenantBaseEntity;

/**
 * Tenant base entity implementation.
 *
 * @param <I> Primary key.
 */
@EqualsAndHashCode(callSuper = true)
@Data
@JacksonStdImpl
@MappedSuperclass
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = String.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@FilterDef(name = "softDeleteFilter")
public class TenantBaseEntityImpl<I extends Serializable> extends BaseEntityImpl<I> implements TenantBaseEntity<I> {

  /**
   * The tenant ID associated with the entity.
   */
  @Column(name = "tenant_id", updatable = false, nullable = false)
  @Schema(hidden = true, accessMode = Schema.AccessMode.READ_ONLY)
  private String tenantId;

  @Override
  public void prePersist() {
    if (tenantId == null) {
      tenantId = "default";
    }
    super.prePersist();
  }
}
