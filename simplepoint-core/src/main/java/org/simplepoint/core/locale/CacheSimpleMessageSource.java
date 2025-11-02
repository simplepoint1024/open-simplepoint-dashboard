package org.simplepoint.core.locale;

import static org.simplepoint.core.concations.CacheNames.SIMPLEPOINT_I18N_MESSAGES;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Component;

/**
 * CacheMessageService is a Spring component that provides caching for message retrieval.
 * It uses the MessageService to fetch messages and caches the results to improve performance.
 */
@Component
@ConditionalOnBean(I18nMessageService.class)
public class CacheSimpleMessageSource {
  private final I18nMessageService messageService;

  /**
   * Constructs a CacheMessageService with the provided MessageService.
   *
   * @param messageService the MessageService to use for retrieving messages
   */
  public CacheSimpleMessageSource(I18nMessageService messageService) {
    this.messageService = messageService;
  }

  /**
   * Retrieves a message based on the provided code and locale.
   * This method is cached to improve performance.
   *
   * @param code   the code of the message
   * @param locale the locale for which the message is requested
   * @return the resolved message or null if not found
   */
  //@Cacheable(value = SIMPLEPOINT_I18N_MESSAGES, key = "#p1 + ':' +#p0")
  public String getMessage(String code, String locale) {
    return messageService.getMessage(code, locale);
  }

  /**
   * Evicts the cached message for the specified code and locale.
   * This method is used to clear the cache when a message is updated or deleted.
   *
   * @param code   the code of the message
   * @param locale the locale for which the message is cached
   */
  @CacheEvict(value = SIMPLEPOINT_I18N_MESSAGES, key = "#p1 + ':' +#p0")
  public void evictMessage(String code, String locale) {
  }
}
