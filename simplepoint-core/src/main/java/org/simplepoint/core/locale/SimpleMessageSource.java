package org.simplepoint.core.locale;

import java.text.MessageFormat;
import java.util.Locale;
import org.simplepoint.api.exception.NotImplementedException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * SimpleMessageSource is a custom implementation of MessageSource that retrieves messages
 * from a MessageService. It formats messages with parameters and provides default messages
 * when necessary.
 */
@Primary
@Component
@ConditionalOnBean(CacheSimpleMessageSource.class)
public class SimpleMessageSource implements MessageSource {

  private final CacheSimpleMessageSource cacheMessageService;

  /**
   * Constructs a SimpleMessageSource with the provided CacheMessageService.
   *
   * @param cacheMessageService the CacheMessageService to use for retrieving messages
   */
  public SimpleMessageSource(CacheSimpleMessageSource cacheMessageService) {
    this.cacheMessageService = cacheMessageService;
  }

  /**
   * Retrieves a message based on the provided code and locale.
   * If the message is not found, it returns the code itself.
   *
   * @param code   the code of the message
   * @param locale the locale for which the message is requested
   * @return the resolved message or the code if not found
   */
  @Override
  public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
    String message = this.getMessage(code, locale.toString());
    if (message == null || message.isEmpty()) {
      if (defaultMessage == null || defaultMessage.isEmpty()) {
        return code;
      }
      return MessageFormat.format(defaultMessage, args);
    }
    return MessageFormat.format(message, args);
  }

  /**
   * Retrieves a message based on the provided code and locale.
   * If the message is not found, it returns the code itself.
   *
   * @param code   the code of the message
   * @param locale the locale for which the message is requested
   * @return the resolved message or the code if not found
   */
  @Override
  public String getMessage(String code, Object[] args, Locale locale)
      throws NoSuchMessageException {
    String message = this.getMessage(code, locale.toString());
    if (message == null || message.isEmpty()) {
      return code;
    }
    return MessageFormat.format(message, args);
  }

  /**
   * Retrieves a message based on the provided MessageSourceResolvable and locale.
   * This method is not implemented and will throw a NotImplementedException.
   *
   * @param resolvable the MessageSourceResolvable to resolve the message
   * @param locale     the locale for which the message is requested
   * @return this method is not implemented
   * @throws NoSuchMessageException if the message cannot be resolved
   */
  @Override
  public String getMessage(MessageSourceResolvable resolvable, Locale locale)
      throws NoSuchMessageException {
    throw new NotImplementedException();
  }

  /**
   * Retrieves a message based on the provided code and locale.
   * This method uses the CacheMessageService to retrieve the message.
   *
   * @param code   the code of the message
   * @param locale the locale for which the message is requested
   * @return the resolved message or null if not found
   */
  private String getMessage(String code, String locale) {
    return cacheMessageService.getMessage(code, locale);
  }

}
