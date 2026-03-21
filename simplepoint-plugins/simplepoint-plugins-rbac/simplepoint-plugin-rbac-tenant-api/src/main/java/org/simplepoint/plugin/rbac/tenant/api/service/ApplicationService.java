package org.simplepoint.plugin.rbac.tenant.api.service;

import java.util.Collection;
import java.util.Set;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.rbac.tenant.api.entity.Application;
import org.simplepoint.plugin.rbac.tenant.api.entity.ApplicationFeatureRelevance;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.ApplicationFeaturesRelevanceDto;

/**
 * ApplicationService is an interface that defines methods for managing application-related operations.
 */
public interface ApplicationService extends BaseService<Application, String> {

  /**
   * Loads feature codes assigned to the application.
   *
   * @param applicationCode application code
   * @return feature codes
   */
  Collection<String> authorizedFeatures(String applicationCode);

  /**
   * Assigns features to the application.
   *
   * @param dto application feature dto
   * @return saved relation rows
   */
  Collection<ApplicationFeatureRelevance> authorizeFeatures(ApplicationFeaturesRelevanceDto dto);

  /**
   * Removes feature assignments from the application.
   *
   * @param applicationCode application code
   * @param featureCodes feature codes to remove
   */
  void unauthorizedFeatures(String applicationCode, Set<String> featureCodes);
}
