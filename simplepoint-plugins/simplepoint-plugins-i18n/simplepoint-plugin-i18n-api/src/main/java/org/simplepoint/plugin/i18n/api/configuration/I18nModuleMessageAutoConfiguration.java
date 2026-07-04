package org.simplepoint.plugin.i18n.api.configuration;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.core.entity.Message;
import org.simplepoint.core.utils.ClassPathResourceUtil;
import org.simplepoint.plugin.i18n.api.entity.Namespace;
import org.simplepoint.plugin.i18n.api.service.I18nMessageRegistrationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * Registers module-owned i18n messages from classpath declarations.
 */
@Slf4j
@AutoConfiguration
public class I18nModuleMessageAutoConfiguration implements ApplicationRunner {

  private static final String MODULE_MESSAGES_PATH = "/META-INF/simplepoint/i18n/";

  private static final Set<String> GLOBAL_NAMESPACES = Set.of(
      "common",
      "errors",
      "messages",
      "settings"
  );

  private final ObjectProvider<I18nMessageRegistrationService> registrationServiceProvider;
  private final String applicationName;

  /**
   * Creates module i18n auto-configuration.
   */
  public I18nModuleMessageAutoConfiguration(
      ObjectProvider<I18nMessageRegistrationService> registrationServiceProvider,
      @Value("${spring.application.name:unknown}")
      String applicationName
  ) {
    this.registrationServiceProvider = registrationServiceProvider;
    this.applicationName = applicationName;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    I18nMessageRegistrationService registrationService = registrationServiceProvider.getIfAvailable();
    if (registrationService == null) {
      log.debug("I18nMessageRegistrationService bean not available, skipping module i18n initialization");
      return;
    }

    Map<String, Map<String, Map<String, String>>> messages = ClassPathResourceUtil
        .readJsonPathMap(MODULE_MESSAGES_PATH);
    if (messages == null || messages.isEmpty()) {
      log.debug("Module i18n message declarations not found under {}", MODULE_MESSAGES_PATH);
      return;
    }

    ModuleMessages moduleMessages = buildModuleMessages(messages, registrationService);
    if (moduleMessages.namespaces().isEmpty() && moduleMessages.messages().isEmpty()) {
      log.debug("Module i18n message declarations already registered for service: {}", applicationName);
      return;
    }

    registrationService.register(moduleMessages.namespaces(), moduleMessages.messages());
    log.info(
        "Registered module i18n declarations for service: {}, namespaces: {}, messages: {}",
        applicationName,
        moduleMessages.namespaces().size(),
        moduleMessages.messages().size()
    );
  }

  private ModuleMessages buildModuleMessages(
      Map<String, Map<String, Map<String, String>>> load,
      I18nMessageRegistrationService registrationService
  ) {
    Set<String> namespaceCodesToImport = new HashSet<>();
    for (Map<String, Map<String, String>> namespaceMessages : load.values()) {
      namespaceCodesToImport.addAll(namespaceMessages.keySet());
    }
    if (namespaceCodesToImport.isEmpty()) {
      return new ModuleMessages(Set.of(), Set.of());
    }

    Set<String> existingNamespaceCodes = new HashSet<>(registrationService.findExistingNamespaceCodes());
    Set<String> existingKeys = new HashSet<>(registrationService.findExistingMessageKeys(namespaceCodesToImport));
    Set<Namespace> namespaceSet = new LinkedHashSet<>();
    Set<Message> messageSet = new LinkedHashSet<>();

    for (Map.Entry<String, Map<String, Map<String, String>>> localeEntry : load.entrySet()) {
      String locale = localeEntry.getKey();
      for (Map.Entry<String, Map<String, String>> namespaceEntry : localeEntry.getValue().entrySet()) {
        String namespaceCode = namespaceEntry.getKey();
        if (!existingNamespaceCodes.contains(namespaceCode)) {
          namespaceSet.add(new Namespace(namespaceCode, namespaceCode));
          existingNamespaceCodes.add(namespaceCode);
        }
        for (Map.Entry<String, String> messageEntry : namespaceEntry.getValue().entrySet()) {
          String compositeKey = locale + ":" + namespaceCode + ":" + messageEntry.getKey();
          if (existingKeys.contains(compositeKey)) {
            continue;
          }
          Message message = new Message();
          message.setLocale(locale);
          message.setNamespace(namespaceCode);
          message.setCode(messageEntry.getKey());
          message.setMessage(messageEntry.getValue());
          message.setGlobal(GLOBAL_NAMESPACES.contains(namespaceCode));
          messageSet.add(message);
        }
      }
    }
    return new ModuleMessages(namespaceSet, messageSet);
  }

  private record ModuleMessages(Set<Namespace> namespaces, Set<Message> messages) {
  }
}
