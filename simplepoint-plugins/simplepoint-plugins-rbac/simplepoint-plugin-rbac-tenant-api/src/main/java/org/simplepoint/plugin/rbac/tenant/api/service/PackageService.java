package org.simplepoint.plugin.rbac.tenant.api.service;

import java.util.Collection;
import java.util.Set;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.rbac.tenant.api.entity.PackageApplicationRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.Package;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.PackageApplicationsRelevanceDto;

/**
 * PackageService is an interface that defines methods for managing package-related operations.
 */
public interface PackageService extends BaseService<Package, String> {

  /**
   * Loads application codes assigned to the package.
   *
   * @param packageCode package code
   * @return application codes
   */
  Collection<String> authorizedApplications(String packageCode);

  /**
   * Assigns applications to the package.
   *
   * @param dto package application dto
   * @return saved relation rows
   */
  Collection<PackageApplicationRelevance> authorizeApplications(PackageApplicationsRelevanceDto dto);

  /**
   * Removes application assignments from the package.
   *
   * @param packageCode package code
   * @param applicationCodes application codes to remove
   */
  void unauthorizedApplications(String packageCode, Set<String> applicationCodes);
}
