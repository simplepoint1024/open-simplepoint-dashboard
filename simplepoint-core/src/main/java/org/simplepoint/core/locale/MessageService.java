package org.simplepoint.core.locale;

import java.util.Map;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.core.entity.Message;
import org.simplepoint.data.amqp.annotation.AmqpRemoteClient;

/**
 * MessageService provides an interface for managing messages in the internationalization system.
 * It extends BaseService to inherit common service methods for message entities.
 * This interface is parameterized with Message type and String as the ID type.
 */
@AmqpRemoteClient(to = "i18n.message")
public interface MessageService extends BaseService<Message, String> {
  /**
   * Retrieves a message based on the provided code and language.
   * If the message is not found, it returns null.
   *
   * @param code     the code of the message
   * @param language the language of the message
   * @return the text of the message or null if not found
   */
  String getMessage(String code, String language);

  /**
   * Retrieves mapping messages based on the provided locale and namespace.
   *
   * @param locale the locale for which messages are requested
   * @param ns     the namespace of the messages
   * @return a map of message codes to their corresponding texts
   */
  Map<String, String> mapping(String locale, String ns);
}
