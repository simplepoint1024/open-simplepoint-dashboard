package org.simplepoint.plugin.i18n.service.impl;

import java.util.Map;
import java.util.stream.Collectors;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.i18n.api.entity.Language;
import org.simplepoint.plugin.i18n.api.repository.I18nLanguageRepository;
import org.simplepoint.plugin.i18n.api.service.I18nLanguageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * LanguageServiceImpl provides the implementation for LanguageService.
 * It extends BaseServiceImpl to manage Language entities.
 * This service is used to interact with the persistence layer for country entities.
 */
@Primary
@Service
public class I18nLanguageServiceImpl extends BaseServiceImpl<I18nLanguageRepository, Language, String>
    implements I18nLanguageService {

  /**
   * Constructs a BaseServiceImpl with the specified repository, user context, and details provider service.
   *
   * @param repository                 the repository to be used for entity operations
   * @param detailsProviderService     the service providing additional details
   */
  public I18nLanguageServiceImpl(
      I18nLanguageRepository repository,
      DetailsProviderService detailsProviderService
  ) {
    super(repository, detailsProviderService);
  }

  @Override
  public Map<String, String> mapping() {
    return getRepository().mapping().stream().collect(Collectors.toMap(Language::getLocale, Language::getNameEnglish));
  }
}
