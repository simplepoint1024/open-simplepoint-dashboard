package org.simplepoint.plugin.rbac.tenant.api.entity;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The resource ID is unique inside a tenant; imported IDs equal platform IDs. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TenantResourceKey implements Serializable {
  private String tenantId;
  private String id;
}
