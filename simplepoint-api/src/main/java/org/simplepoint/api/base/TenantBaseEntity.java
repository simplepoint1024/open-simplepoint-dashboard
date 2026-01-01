package org.simplepoint.api.base;

import java.io.Serializable;

/**
 * Tenant base entity.
 *
 * @param <I> Primary key.
 */
public interface TenantBaseEntity<I extends Serializable> extends BaseEntity<I> {
  /**
   * getter tenant id.
   *
   * @return tenant id.
   */
  String getTenantId();

  /**
   * setter tenant id.
   *
   * @param tenantId tenant id.
   */
  void setTenantId(String tenantId);
}
