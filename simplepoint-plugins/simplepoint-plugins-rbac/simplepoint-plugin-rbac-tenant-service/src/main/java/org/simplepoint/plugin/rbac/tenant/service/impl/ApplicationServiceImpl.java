package org.simplepoint.plugin.rbac.tenant.service.impl;

import org.simplepoint.api.security.base.BaseUser;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.rbac.tenant.api.entity.Application;
import org.simplepoint.plugin.rbac.tenant.api.repository.ApplicationRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.ApplicationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * ApplicationServiceImpl provides the implementation for ApplicationService.
 * It extends BaseServiceImpl to manage Application entities.
 */
@Service
public class ApplicationServiceImpl
    extends BaseServiceImpl<ApplicationRepository, Application, String>
    implements ApplicationService {

  public ApplicationServiceImpl(
      ApplicationRepository repository,
      @Autowired(required = false) final AuthorizationContextHolder authorizationContextHolder,
      DetailsProviderService detailsProviderService
  ) {
    super(repository, authorizationContextHolder, detailsProviderService);
  }
}
