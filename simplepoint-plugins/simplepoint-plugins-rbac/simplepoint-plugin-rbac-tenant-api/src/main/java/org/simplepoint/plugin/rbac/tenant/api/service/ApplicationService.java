package org.simplepoint.plugin.rbac.tenant.api.service;

import java.util.Collection;
import java.util.Set;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.rbac.tenant.api.entity.Application;
import org.simplepoint.plugin.rbac.tenant.api.entity.ApplicationResourceRelevance;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.ApplicationResourcesRelevanceDto;

/**
 * Application service.
 */
public interface ApplicationService extends BaseService<Application, String> {

  /**
   * Loads resource codes assigned to the application.
   */
  Collection<String> authorizedResources(String applicationCode);

  /**
   * Assigns resources to the application.
   */
  Collection<ApplicationResourceRelevance> authorizeResources(ApplicationResourcesRelevanceDto dto);

  /**
   * Removes resource assignments from the application.
   */
  void unauthorizedResources(String applicationCode, Set<String> resourceCodes);
}
