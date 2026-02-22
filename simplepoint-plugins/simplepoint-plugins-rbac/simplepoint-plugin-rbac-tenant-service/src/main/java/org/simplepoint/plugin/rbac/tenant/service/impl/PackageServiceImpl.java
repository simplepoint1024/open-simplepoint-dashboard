package org.simplepoint.plugin.rbac.tenant.service.impl;

import org.simplepoint.api.security.base.BaseUser;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.rbac.tenant.api.entity.Package;
import org.simplepoint.plugin.rbac.tenant.api.repository.PackageRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.PackageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * PackageServiceImpl provides the implementation for PackageService.
 * It extends BaseServiceImpl to manage Package entities.
 */
@Service
public class PackageServiceImpl
    extends BaseServiceImpl<PackageRepository, Package, String>
    implements PackageService {

  /**
   * Constructs a PackageServiceImpl.
   *
   * @param repository             the repository to be used for entity operations
   * @param userContext            the user context for accessing user information
   * @param detailsProviderService the service providing additional details
   */
  public PackageServiceImpl(
      PackageRepository repository,
      @Autowired(required = false) final AuthorizationContextHolder authorizationContextHolder,
      DetailsProviderService detailsProviderService
  ) {
    super(repository, authorizationContextHolder, detailsProviderService);
  }
}
