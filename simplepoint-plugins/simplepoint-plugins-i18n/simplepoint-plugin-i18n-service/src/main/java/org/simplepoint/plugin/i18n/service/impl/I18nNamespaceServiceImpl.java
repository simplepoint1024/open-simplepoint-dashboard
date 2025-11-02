package org.simplepoint.plugin.i18n.service.impl;

import org.simplepoint.api.security.base.BaseUser;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.core.context.UserContext;
import org.simplepoint.plugin.i18n.api.entity.Namespace;
import org.simplepoint.plugin.i18n.api.repository.I18nNamespaceRepository;
import org.simplepoint.plugin.i18n.api.service.I18nNamespaceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * NamespaceServiceImpl provides the implementation for I18nNamespaceService.
 * It extends BaseServiceImpl to manage Namespace entities.
 * This service is used to interact with the persistence layer for namespace entities.
 */
@Service
public class I18nNamespaceServiceImpl extends BaseServiceImpl<I18nNamespaceRepository, Namespace, String> implements I18nNamespaceService {

  /**
   * Constructs a BaseServiceImpl with the specified repository, user context, and details provider service.
   *
   * @param repository             the repository to be used for entity operations
   * @param userContext            the user context for accessing user-related information
   * @param detailsProviderService the service providing additional details
   */
  public I18nNamespaceServiceImpl(
      I18nNamespaceRepository repository,
      @Autowired(required = false) UserContext<BaseUser> userContext,
      DetailsProviderService detailsProviderService) {
    super(repository, userContext, detailsProviderService);
  }
}
