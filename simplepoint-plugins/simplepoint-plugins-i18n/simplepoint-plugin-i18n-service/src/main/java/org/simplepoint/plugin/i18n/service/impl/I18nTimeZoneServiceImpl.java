package org.simplepoint.plugin.i18n.service.impl;

import org.simplepoint.api.security.base.BaseUser;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.core.context.UserContext;
import org.simplepoint.plugin.i18n.api.entity.TimeZone;
import org.simplepoint.plugin.i18n.api.repository.I18nTimeZoneRepository;
import org.simplepoint.plugin.i18n.api.service.I18nTimeZoneService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * TimeZoneServiceImpl provides the implementation for TimeZoneService.
 * It extends BaseServiceImpl to manage TimeZone entities.
 * This service is used to interact with the persistence layer for time zone entities.
 */
@Service
public class I18nTimeZoneServiceImpl extends BaseServiceImpl<I18nTimeZoneRepository, TimeZone, String>
    implements
    I18nTimeZoneService {

  /**
   * Constructs a BaseServiceImpl with the specified repository, user context, and details provider service.
   *
   * @param repository             the repository to be used for entity operations
   * @param userContext            the user context for accessing user-related information
   * @param detailsProviderService the service providing additional details
   */
  public I18nTimeZoneServiceImpl(I18nTimeZoneRepository repository,
                                 @Autowired(required = false) UserContext<BaseUser> userContext,
                                 DetailsProviderService detailsProviderService) {
    super(repository, userContext, detailsProviderService);
  }
}
