package org.simplepoint.plugin.i18n.service.impl;

import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.core.entity.Message;
import org.simplepoint.core.locale.MessageService;
import org.simplepoint.data.amqp.annotation.AmqpRemoteService;
import org.simplepoint.plugin.i18n.api.repository.MessageRepository;

/**
 * MessageServiceImpl provides the implementation for MessageService.
 * It extends BaseServiceImpl to manage Message entities.
 * This service is used to interact with the persistence layer for message entities.
 */
@AmqpRemoteService
public class MessageServiceImpl extends BaseServiceImpl<MessageRepository, Message, String> implements
    MessageService {

  /**
   * Constructs a BaseServiceImpl with the specified repository and access metadata sync service.
   *
   * @param repository the repository to be used for entity operations
   */
  public MessageServiceImpl(MessageRepository repository) {
    super(repository);
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
}
