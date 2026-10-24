package org.simplepoint.plugin.i18n.api.initialization;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.i18n.api.model.MessageDeclaration;
import org.simplepoint.plugin.i18n.api.model.NamespaceDeclaration;
import org.simplepoint.plugin.i18n.api.service.I18nMessageRegistrationService;

/** Shares additive registration semantics between legacy hosts and selected module startup. */
public final class I18nDeclarationRegistration {
  private static final Set<String> GLOBAL_NAMESPACES =
      Set.of("common", "errors", "messages", "settings");

  private I18nDeclarationRegistration() {}

  /** Reads both historical declarations and migrated module assets for legacy service hosts. */
  public static Map<String, Map<String, Map<String, String>>> readLegacyDeclarations()
      throws java.io.IOException {
    var messages =
        org.simplepoint.core.utils.ClassPathResourceUtil.readJsonPathMap(
            "/META-INF/simplepoint/i18n/");
    var modular =
        org.simplepoint.core.utils.ClassPathResourceUtil.readJsonPathMap(
            "/META-INF/simplepoint/modules/*/i18n/");
    modular.forEach(
        (locale, namespaces) ->
            namespaces.forEach(
                (namespace, entries) ->
                    messages
                        .computeIfAbsent(locale, ignored -> new java.util.HashMap<>())
                        .computeIfAbsent(namespace, ignored -> new java.util.HashMap<>())
                        .putAll(entries)));
    return messages;
  }

  /**
   * Adds missing declarations, preserving previously registered or administrator-edited messages.
   */
  public static void register(
      Map<String, Map<String, Map<String, String>>> messages,
      I18nMessageRegistrationService service) {
    var declarations = buildModuleMessages(messages, service);
    if (!declarations.namespaces().isEmpty() || !declarations.messages().isEmpty()) {
      service.register(declarations.namespaces(), declarations.messages());
    }
  }

  private static ModuleMessages buildModuleMessages(
      Map<String, Map<String, Map<String, String>>> load,
      I18nMessageRegistrationService registrationService) {
    Set<String> namespaceCodesToImport = new HashSet<>();
    for (Map<String, Map<String, String>> namespaceMessages : load.values()) {
      namespaceCodesToImport.addAll(namespaceMessages.keySet());
    }
    if (namespaceCodesToImport.isEmpty()) {
      return new ModuleMessages(Set.of(), Set.of());
    }

    Set<String> existingNamespaceCodes =
        new HashSet<>(registrationService.findExistingNamespaceCodes());
    Set<String> existingKeys =
        new HashSet<>(registrationService.findExistingMessageKeys(namespaceCodesToImport));
    Set<NamespaceDeclaration> namespaceSet = new LinkedHashSet<>();
    Set<MessageDeclaration> messageSet = new LinkedHashSet<>();

    for (Map.Entry<String, Map<String, Map<String, String>>> localeEntry : load.entrySet()) {
      String locale = localeEntry.getKey();
      for (Map.Entry<String, Map<String, String>> namespaceEntry :
          localeEntry.getValue().entrySet()) {
        String namespaceCode = namespaceEntry.getKey();
        if (!existingNamespaceCodes.contains(namespaceCode)) {
          namespaceSet.add(new NamespaceDeclaration(namespaceCode, namespaceCode, null, null));
          existingNamespaceCodes.add(namespaceCode);
        }
        for (Map.Entry<String, String> messageEntry : namespaceEntry.getValue().entrySet()) {
          String compositeKey = locale + ":" + namespaceCode + ":" + messageEntry.getKey();
          if (existingKeys.contains(compositeKey)) {
            continue;
          }
          messageSet.add(
              new MessageDeclaration(
                  locale,
                  namespaceCode,
                  messageEntry.getKey(),
                  messageEntry.getValue(),
                  null,
                  GLOBAL_NAMESPACES.contains(namespaceCode)));
        }
      }
    }
    return new ModuleMessages(namespaceSet, messageSet);
  }

  private record ModuleMessages(
      Set<NamespaceDeclaration> namespaces, Set<MessageDeclaration> messages) {}
}
