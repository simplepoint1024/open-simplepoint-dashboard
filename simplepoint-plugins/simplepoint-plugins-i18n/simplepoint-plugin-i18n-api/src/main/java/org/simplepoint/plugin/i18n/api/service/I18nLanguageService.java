package org.simplepoint.plugin.i18n.api.service;

import java.util.Map;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.data.amqp.annotation.AmqpRemoteClient;
import org.simplepoint.plugin.i18n.api.entity.Language;

/**
 * LanguageService provides an interface for managing languages in the internationalization system.
 * It is used to interact with the persistence layer for language entities.
 * This interface does not define any methods, as it serves as a marker interface for service implementations.
 */
@AmqpRemoteClient(to = "i18n.language")
public interface I18nLanguageService extends BaseService<Language, String> {
  /**
   * Retrieves a mapping of language codes to their corresponding language names.
   *
   * @return A map where the key is the language code and the value is the language name.
   */
  Map<String, String> mapping();
}
