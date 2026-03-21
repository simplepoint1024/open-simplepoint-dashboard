package org.simplepoint.plugin.rbac.tenant.api.pojo.dto;

import java.util.Set;
import lombok.Data;

/**
 * Package application relevance dto.
 */
@Data
public class PackageApplicationsRelevanceDto {
  private String packageCode;
  private Set<String> applicationCodes;
}
