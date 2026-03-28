package org.simplepoint.plugin.rbac.tenant.api.service;

import java.util.Collection;
import java.util.Set;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.rbac.tenant.api.entity.Feature;
import org.simplepoint.plugin.rbac.tenant.api.entity.FeaturePermissionRelevance;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.FeaturePermissionsRelevanceDto;

/**
 * FeatureService is an interface that defines methods for managing feature-related operations.
 * It is part of the RBAC (Role-Based Access Control) module of the SimplePoint application.
 * This service will handle operations related to features, such as retrieving feature information,
 * managing feature data, and other related functionalities.
 */
public interface FeatureService extends BaseService<Feature, String> {

  /**
   * Loads permission authorities assigned to the feature.
   *
   * @param featureCode feature code
   * @return permission authorities
   */
  Collection<String> authorizedPermissions(String featureCode);

  /**
   * Loads feature detail rows by codes.
   *
   * @param featureCodes feature codes
   * @return matched feature rows
   */
  Collection<Feature> findAllByCodes(Collection<String> featureCodes);

  /**
   * Assigns permissions to the feature.
   *
   * @param dto feature permission dto
   * @return saved relation rows
   */
  Collection<FeaturePermissionRelevance> authorizePermissions(FeaturePermissionsRelevanceDto dto);

  /**
   * Removes permissions from the feature.
   *
   * @param featureCode feature code
   * @param permissionAuthority authorities to remove
   */
  void unauthorizedPermissions(String featureCode, Set<String> permissionAuthority);
}
