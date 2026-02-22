package org.simplepoint.plugin.rbac.tenant.service.impl;

import org.simplepoint.api.security.base.BaseUser;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.rbac.tenant.api.entity.Feature;
import org.simplepoint.plugin.rbac.tenant.api.repository.FeatureRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.FeatureService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * FeatureServiceImpl provides the implementation for FeatureService.
 * It extends BaseServiceImpl to manage Feature entities.
 * This service is used to interact with the persistence layer for feature entities.
 */
@Service
public class FeatureServiceImpl extends BaseServiceImpl<FeatureRepository, Feature, String> implements FeatureService {

  /**
   * Constructs a BaseServiceImpl with the specified repository, user context, and details provider service.
   *
   * @param repository             the repository to be used for entity operations
   * @param authorizationContextHolder            用于访问与用户相关信息的用户上下文
   * @param detailsProviderService the service providing additional details
   */
  public FeatureServiceImpl(
      FeatureRepository repository,
      @Autowired(required = false) final AuthorizationContextHolder authorizationContextHolder,
      DetailsProviderService detailsProviderService
  ) {
    super(repository, authorizationContextHolder, detailsProviderService);
  }
}
