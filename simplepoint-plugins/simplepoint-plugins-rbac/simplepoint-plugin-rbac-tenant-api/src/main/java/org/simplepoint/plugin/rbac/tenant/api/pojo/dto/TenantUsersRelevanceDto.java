package org.simplepoint.plugin.rbac.tenant.api.pojo.dto;

import java.util.Set;
import lombok.Data;

/**
 * Tenant user relevance dto.
 */
@Data
public class TenantUsersRelevanceDto {
  private String tenantId;
  private Set<String> userIds;
}
