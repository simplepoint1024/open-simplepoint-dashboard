package org.simplepoint.plugin.rbac.tenant.api.entity;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One purchased resource identity within one package allocation. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TenantPackageResourceKey implements Serializable {
  private String instanceId;
  private String resourceId;
}
