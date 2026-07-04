package org.simplepoint.plugin.i18n.service.impl;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.core.entity.Message;
import org.simplepoint.core.locale.I18nMessageService;
import org.simplepoint.plugin.i18n.api.entity.Namespace;
import org.simplepoint.plugin.i18n.api.repository.I18nMessageRepository;
import org.simplepoint.plugin.i18n.api.service.I18nMessageRegistrationService;
import org.simplepoint.plugin.i18n.api.service.I18nNamespaceService;
import org.simplepoint.remoting.RemoteProvider;
import org.springframework.stereotype.Service;

/**
 * Default module i18n message registration service.
 */
@Service
@RemoteProvider
public class I18nMessageRegistrationServiceImpl implements I18nMessageRegistrationService {

  private final I18nNamespaceService namespaceService;
  private final I18nMessageService messageService;
  private final I18nMessageRepository messageRepository;

  /**
   * Creates a module message registration service.
   */
  public I18nMessageRegistrationServiceImpl(
      I18nNamespaceService namespaceService,
      I18nMessageService messageService,
      I18nMessageRepository messageRepository
  ) {
    this.namespaceService = namespaceService;
    this.messageService = messageService;
    this.messageRepository = messageRepository;
  }

  @Override
  public Set<String> findExistingNamespaceCodes() {
    return namespaceService.findAll(Map.of()).stream()
        .map(Namespace::getCode)
        .filter(code -> code != null && !code.isBlank())
        .collect(Collectors.toSet());
  }

  @Override
  public Set<String> findExistingMessageKeys(Collection<String> namespaces) {
    return messageRepository.findExistingKeys(namespaces);
  }

  @Override
  public void register(Set<Namespace> namespaces, Set<Message> messages) {
    if (namespaces != null && !namespaces.isEmpty()) {
      namespaceService.create(namespaces);
    }
    if (messages != null && !messages.isEmpty()) {
      messageService.create(messages);
    }
  }
}
