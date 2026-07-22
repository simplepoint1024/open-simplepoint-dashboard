package org.simplepoint.plugin.i18n.service.impl;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.i18n.api.entity.Language;
import org.simplepoint.plugin.i18n.api.repository.I18nLanguageRepository;
import org.simplepoint.plugin.i18n.api.repository.I18nMessageRepository;
import org.simplepoint.plugin.i18n.api.service.I18nLanguageService;
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

  private final I18nMessageRepository messageRepository;

  /**
   * Constructs a BaseServiceImpl with the specified repository, user context, and details provider service.
   *
   * @param repository                 the repository to be used for entity operations
   * @param detailsProviderService     the service providing additional details
   */
  public I18nLanguageServiceImpl(
      I18nLanguageRepository repository,
      I18nMessageRepository messageRepository,
      DetailsProviderService detailsProviderService
  ) {
    super(repository, detailsProviderService);
    this.messageRepository = messageRepository;
  }

  @Override
  protected boolean isDataScopeApplicable() {
    return false;
  }

  @Override
  public Map<String, String> mapping() {
    Set<String> availableLocales = messageRepository.findAvailableLocales().stream()
        .map(I18nLanguageServiceImpl::normalizeLocale)
        .collect(Collectors.toSet());

    return getRepository().mapping().stream()
        .filter(language -> availableLocales.contains(normalizeLocale(language.getLocale())))
        .collect(Collectors.toMap(
            language -> normalizeLocale(language.getLocale()),
            Language::getNameEnglish,
            (left, right) -> left,
            LinkedHashMap::new
        ));
  }

  private static String normalizeLocale(String locale) {
    if (locale == null || locale.isBlank()) {
      return "";
    }

    String[] parts = locale.trim().replace('_', '-').split("-", 2);
    if (parts.length == 1) {
      return parts[0].toLowerCase();
    }
    return parts[0].toLowerCase() + "-" + parts[1].toUpperCase();
  }
}
