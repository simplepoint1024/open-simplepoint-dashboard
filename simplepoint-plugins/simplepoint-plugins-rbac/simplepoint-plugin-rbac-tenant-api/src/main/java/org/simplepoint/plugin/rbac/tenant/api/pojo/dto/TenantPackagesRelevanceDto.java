package org.simplepoint.plugin.rbac.tenant.api.pojo.dto;

import java.util.Set;
import lombok.Data;

/**
 * Tenant package relevance dto.
 */
@Data
public class TenantPackagesRelevanceDto {
  private String tenantId;
  private Set<String> packageCodes;
}
