package org.simplepoint.plugin.i18n.api.service;

import java.util.Collection;
import java.util.Set;
import org.simplepoint.plugin.i18n.api.model.MessageDeclaration;
import org.simplepoint.plugin.i18n.api.model.NamespaceDeclaration;
import org.simplepoint.remoting.RemoteContract;

/**
 * Registers module-owned i18n namespaces and messages.
 */
@RemoteContract(name = "i18n.message-registration", version = "2")
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
  void register(Set<NamespaceDeclaration> namespaces, Set<MessageDeclaration> messages);
}
