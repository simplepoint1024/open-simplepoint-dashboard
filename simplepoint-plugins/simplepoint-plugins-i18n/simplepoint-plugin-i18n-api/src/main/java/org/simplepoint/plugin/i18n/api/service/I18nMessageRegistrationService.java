package org.simplepoint.plugin.i18n.api.service;

import java.util.Collection;
import java.util.Set;
import org.simplepoint.core.entity.Message;
import org.simplepoint.plugin.i18n.api.entity.Namespace;
import org.simplepoint.remoting.RemoteContract;

/**
 * Registers module-owned i18n namespaces and messages.
 */
@RemoteContract(name = "i18n.message-registration")
public interface I18nMessageRegistrationService {

  /**
   * Finds namespace codes that already exist.
   *
   * @return namespace codes
   */
  Set<String> findExistingNamespaceCodes();

  /**
   * Finds existing message keys in {@code locale:namespace:code} format.
   *
   * @param namespaces namespace codes to inspect
   * @return existing message keys
   */
  Set<String> findExistingMessageKeys(Collection<String> namespaces);

  /**
   * Registers missing namespaces and messages.
   *
   * @param namespaces namespaces to create
   * @param messages messages to create
   */
  void register(Set<Namespace> namespaces, Set<Message> messages);
}
