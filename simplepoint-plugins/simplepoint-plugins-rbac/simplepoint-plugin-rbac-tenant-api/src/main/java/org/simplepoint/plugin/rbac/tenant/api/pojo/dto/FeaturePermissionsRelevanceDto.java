package org.simplepoint.plugin.rbac.tenant.api.pojo.dto;

import java.util.Set;
import lombok.Data;

/**
 * Feature permission relevance dto.
 */
@Data
public class FeaturePermissionsRelevanceDto {
  private String featureCode;
  private Set<String> permissionAuthority;
}
