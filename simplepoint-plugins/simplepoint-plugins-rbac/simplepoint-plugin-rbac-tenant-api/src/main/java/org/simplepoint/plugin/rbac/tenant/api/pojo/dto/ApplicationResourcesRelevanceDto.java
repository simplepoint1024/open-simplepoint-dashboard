package org.simplepoint.plugin.rbac.tenant.api.pojo.dto;

import java.util.Set;
import lombok.Data;

/**
 * Application resource relevance dto.
 */
@Data
public class ApplicationResourcesRelevanceDto {
  private Set<String> resourceCodes;
  private String applicationCode;
}
