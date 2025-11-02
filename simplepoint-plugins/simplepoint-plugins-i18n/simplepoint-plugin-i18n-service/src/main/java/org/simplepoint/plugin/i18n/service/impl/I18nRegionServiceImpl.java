package org.simplepoint.plugin.i18n.service.impl;

import org.simplepoint.api.security.base.BaseUser;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.core.context.UserContext;
import org.simplepoint.plugin.i18n.api.entity.Region;
import org.simplepoint.plugin.i18n.api.repository.I18nRegionRepository;
import org.simplepoint.plugin.i18n.api.service.I18nRegionService;
import org.springframework.stereotype.Service;

/**
 * RegionServiceImpl provides the implementation for RegionService.
 * It extends BaseServiceImpl to manage Region entities.
 * This service is used to interact with the persistence layer for region entities.
 */
@Service
public class I18nRegionServiceImpl extends BaseServiceImpl<I18nRegionRepository, Region, String> implements
    I18nRegionService {

  /**
   * Constructs a BaseServiceImpl with the specified repository, user context, and details provider service.
   *
   * @param repository             the repository to be used for entity operations
   * @param userContext            the user context for accessing user-related information
   * @param detailsProviderService the service providing additional details
   */
  public I18nRegionServiceImpl(I18nRegionRepository repository,
                               UserContext<BaseUser> userContext,
                               DetailsProviderService detailsProviderService) {
    super(repository, userContext, detailsProviderService);
  }
}
