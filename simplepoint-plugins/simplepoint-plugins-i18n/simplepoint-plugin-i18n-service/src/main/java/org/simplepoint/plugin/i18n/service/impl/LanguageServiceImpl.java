package org.simplepoint.plugin.i18n.service.impl;

import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.i18n.api.entity.Language;
import org.simplepoint.plugin.i18n.api.repository.LanguageRepository;
import org.simplepoint.plugin.i18n.api.service.LanguageService;
import org.springframework.stereotype.Service;

/**
 * LanguageServiceImpl provides the implementation for LanguageService.
 * It extends BaseServiceImpl to manage Language entities.
 * This service is used to interact with the persistence layer for language entities.
 */
@Service
public class LanguageServiceImpl extends BaseServiceImpl<LanguageRepository, Language, String>
    implements
    LanguageService {
  /**
   * Constructs a BaseServiceImpl with the specified repository and access metadata sync service.
   *
   * @param repository the repository to be used for entity operations
   */
  public LanguageServiceImpl(LanguageRepository repository) {
    super(repository);
  }
}
