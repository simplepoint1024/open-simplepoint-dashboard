package org.simplepoint.plugin.i18n.service.impl;

import java.util.Map;
import java.util.stream.Collectors;
import org.simplepoint.api.security.base.BaseUser;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.core.context.UserContext;
import org.simplepoint.core.entity.Message;
import org.simplepoint.core.locale.I18nMessageService;
import org.simplepoint.data.amqp.annotation.AmqpRemoteService;
import org.simplepoint.plugin.i18n.api.repository.I18nMessageRepository;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * MessageServiceImpl provides the implementation for MessageService.
 * It extends BaseServiceImpl to manage Message entities.
 * This service is used to interact with the persistence layer for message entities.
 */
@AmqpRemoteService
public class I18nMessageServiceImpl extends BaseServiceImpl<I18nMessageRepository, Message, String> implements
    I18nMessageService {

  /**
   * Constructs a BaseServiceImpl with the specified repository, user context, and details provider service.
   *
   * @param repository             the repository to be used for entity operations
   * @param userContext            the user context for accessing user-related information
   * @param detailsProviderService the service providing additional details
   */
  public I18nMessageServiceImpl(
      I18nMessageRepository repository,
      @Autowired(required = false) UserContext<BaseUser> userContext,
      DetailsProviderService detailsProviderService) {
    super(repository, userContext, detailsProviderService);
  }

  /**
   * Retrieves a message based on the provided code and locale.
   * This method uses caching to improve performance.
   *
   * @param code   the code of the message
   * @param locale the locale for which the message is requested
   * @return the text of the message or null if not found
   */
  public String getMessage(String code, String locale) {
    return getRepository().getMessage(code, locale);
  }

  /**
   * Retrieves global messages based on the provided locale and namespace.
   *
   * @param locale the locale for which messages are requested
   * @param ns     the namespace of the messages
   * @return a map of message codes to their corresponding texts
   */
  @Override
  public Map<String, String> mapping(String locale, String ns) {
    boolean notNull = ns != null && !ns.isEmpty();
    return (notNull ? getRepository().mapping(locale, ns.split(",")) : getRepository().global(locale))
        .stream()
        .collect(Collectors.toMap(Message::getCode, Message::getMessage));
  }
}
