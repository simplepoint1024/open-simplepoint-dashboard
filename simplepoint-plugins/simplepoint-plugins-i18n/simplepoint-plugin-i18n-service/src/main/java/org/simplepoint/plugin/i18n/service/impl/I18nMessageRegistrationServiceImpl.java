package org.simplepoint.plugin.i18n.service.impl;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.core.entity.Message;
import org.simplepoint.plugin.i18n.api.entity.Namespace;
import org.simplepoint.plugin.i18n.api.model.MessageDeclaration;
import org.simplepoint.plugin.i18n.api.model.NamespaceDeclaration;
import org.simplepoint.plugin.i18n.api.repository.I18nMessageRepository;
import org.simplepoint.plugin.i18n.api.service.I18nMessageManagementService;
import org.simplepoint.plugin.i18n.api.service.I18nMessageRegistrationService;
import org.simplepoint.plugin.i18n.api.service.I18nNamespaceService;
import org.simplepoint.remoting.RemoteProvider;
import org.springframework.stereotype.Service;

/** Default module i18n message registration service. */
@Service
@RemoteProvider
public class I18nMessageRegistrationServiceImpl implements I18nMessageRegistrationService {

  private final I18nNamespaceService namespaceService;
  private final I18nMessageManagementService messageService;
  private final I18nMessageRepository messageRepository;

  /** Creates a module message registration service. */
  public I18nMessageRegistrationServiceImpl(
      I18nNamespaceService namespaceService,
      I18nMessageManagementService messageService,
      I18nMessageRepository messageRepository) {
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
  public void register(Set<NamespaceDeclaration> namespaces, Set<MessageDeclaration> messages) {
    if (namespaces != null && !namespaces.isEmpty()) {
      namespaceService.create(
          namespaces.stream()
              .map(
                  declaration -> {
                    var namespace = new Namespace(declaration.name(), declaration.code());
                    namespace.setModule(declaration.module());
                    namespace.setDescription(declaration.description());
                    return namespace;
                  })
              .toList());
    }
    if (messages != null && !messages.isEmpty()) {
      messageService.create(
          messages.stream()
              .map(
                  declaration -> {
                    var message = new Message();
                    message.setLocale(declaration.locale());
                    message.setNamespace(declaration.namespace());
                    message.setCode(declaration.code());
                    message.setMessage(declaration.message());
                    message.setDescription(declaration.description());
                    message.setGlobal(declaration.global());
                    return message;
                  })
              .toList());
    }
  }
}
