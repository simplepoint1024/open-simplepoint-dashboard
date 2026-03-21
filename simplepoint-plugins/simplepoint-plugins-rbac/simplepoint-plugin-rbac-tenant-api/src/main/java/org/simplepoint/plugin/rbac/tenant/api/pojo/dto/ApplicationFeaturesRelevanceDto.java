package org.simplepoint.plugin.rbac.tenant.api.pojo.dto;

import java.util.Set;
import lombok.Data;

/**
 * Application feature relevance dto.
 */
@Data
public class ApplicationFeaturesRelevanceDto {
  private String applicationCode;
  private Set<String> featureCodes;
}
