package org.simplepoint.core.locale;

import java.util.Map;
import org.simplepoint.remoting.RemoteContract;

/**
 * Transportable internationalization queries; entity management belongs to the owning module.
 */
@RemoteContract(name = "i18n.message")
public interface I18nMessageService {
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
