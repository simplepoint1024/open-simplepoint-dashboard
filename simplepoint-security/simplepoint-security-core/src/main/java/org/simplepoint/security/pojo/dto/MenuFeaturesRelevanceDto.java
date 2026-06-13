package org.simplepoint.security.pojo.dto;

import java.util.Set;
import lombok.Data;

/**
 * MenuFeaturesRelevanceDto describes the relationship between a menu and feature codes.
 */
@Data
public class MenuFeaturesRelevanceDto {
  private String menuId;
  private Set<String> featureCodes;
  /**
   * Backward-compatible alias for historical payloads.
   */
  @Deprecated
  private Set<String> permissionAuthority;

  /**
   * Menu Features Relevance Dto.
   */
  public MenuFeaturesRelevanceDto(String menuId, Set<String> featureCodes) {
    this.menuId = menuId;
    this.featureCodes = featureCodes;
  }

  /**
   * Menu Features Relevance Dto.
   */
  public MenuFeaturesRelevanceDto() {
  }

  /**
   * Resolved Feature Codes.
   */
  public Set<String> resolvedFeatureCodes() {
    if (featureCodes != null && !featureCodes.isEmpty()) {
      return featureCodes;
    }
    return permissionAuthority;
  }
}
