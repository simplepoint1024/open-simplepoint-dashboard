package org.simplepoint.plugin.i18n.initialize;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.simplepoint.core.entity.Message;
import org.simplepoint.core.locale.I18nMessageService;
import org.simplepoint.core.utils.ClassPathResourceUtil;
import org.simplepoint.platform.bootstrap.BootstrapContribution;
import org.simplepoint.platform.bootstrap.PlatformBootstrapContribution;
import org.simplepoint.plugin.i18n.api.entity.Countries;
import org.simplepoint.plugin.i18n.api.entity.Language;
import org.simplepoint.plugin.i18n.api.entity.Namespace;
import org.simplepoint.plugin.i18n.api.entity.Region;
import org.simplepoint.plugin.i18n.api.entity.TimeZone;
import org.simplepoint.plugin.i18n.api.repository.I18nMessageRepository;
import org.simplepoint.plugin.i18n.api.service.I18nCountriesService;
import org.simplepoint.plugin.i18n.api.service.I18nLanguageService;
import org.simplepoint.plugin.i18n.api.service.I18nNamespaceService;
import org.simplepoint.plugin.i18n.api.service.I18nRegionService;
import org.simplepoint.plugin.i18n.api.service.I18nTimeZoneService;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * I18nAutoRegistrationInitializer automatically registers internationalization data.
 *
 * <p>This class defines platform bootstrap contributions for base i18n data and i18n messages.
 * The bootstrap contributions read classpath JSON files and populate the corresponding services.
 */
@Component
public class I18nAutoRegistrationInitializer {
  private static final Set<String> I18N_GLOBAL_NAMESPACES = Set.of(
      "common",
      "errors",
      "messages",
      "settings"
  );

  private static final String I18N_INIT_MODULE = "i18n-base";

  private static final String I18N_INIT_MSG_MODULE = "i18n-messages";

  private static final String I18N_BOOTSTRAP_REPAIR_MODULE = "i18n-bootstrap-v2";

  private static final String I18N_COUNTRIES_PATH = "/i18n/countries/";

  private static final String I18N_MODULE_MESSAGES_PATH = "/META-INF/simplepoint/i18n/";

  /**
   * Registers a bootstrap contribution for base i18n data.
   *
   * @return a platform bootstrap contribution
   */
  @Bean
  public PlatformBootstrapContribution i18nBaseBootstrapContribution(
      I18nCountriesService countriesService,
      I18nLanguageService languageService,
      I18nRegionService regionService,
      I18nTimeZoneService timeZoneService
  ) {
    return () -> BootstrapContribution.versioned("i18n", "base", I18N_INIT_MODULE, "1", 200, () -> {
      if (languageService.findAll(Map.of()).isEmpty()) {
        importBaseI18nData(loadBaseI18nData(), countriesService, languageService, regionService, timeZoneService);
      }
    });
  }

  /**
   * Registers a bootstrap contribution for i18n messages.
   *
   * @return a platform bootstrap contribution
   */
  @Bean
  public PlatformBootstrapContribution i18nMessagesBootstrapContribution(
      I18nNamespaceService namespaceService,
      I18nMessageService messageService,
      I18nMessageRepository messageRepository
  ) {
    return () -> BootstrapContribution.versioned("i18n", "messages", I18N_INIT_MSG_MODULE, "1", 210, () ->
        importMessages(loadMessages(), namespaceService, messageService, messageRepository, namespace -> true)
    );
  }

  /**
   * Repairs persisted environments where old i18n initialization was marked done even though
   * resource scanning imported no rows.
   *
   * @return a platform bootstrap contribution
   */
  @Bean
  public PlatformBootstrapContribution i18nBootstrapRepairContribution(
      I18nCountriesService countriesService,
      I18nLanguageService languageService,
      I18nRegionService regionService,
      I18nTimeZoneService timeZoneService,
      I18nNamespaceService namespaceService,
      I18nMessageService messageService,
      I18nMessageRepository messageRepository
  ) {
    return () -> BootstrapContribution.versioned("i18n", "repair", I18N_BOOTSTRAP_REPAIR_MODULE, "1", 220, () -> {
      if (!messageRepository.findAvailableLocales().isEmpty()) {
        return;
      }
      if (languageService.findAll(Map.of()).isEmpty()) {
        importBaseI18nData(loadBaseI18nData(), countriesService, languageService, regionService, timeZoneService);
      }
      importMessages(loadMessages(), namespaceService, messageService, messageRepository, namespace -> true);
    });
  }

  private Map<String, I18nInitializeProperties> loadBaseI18nData() throws Exception {
    Map<String, I18nInitializeProperties> data = ClassPathResourceUtil
        .readJson(I18N_COUNTRIES_PATH, I18nInitializeProperties.class);
    verifyI18n(data);
    return data;
  }

  private Map<String, Map<String, Map<String, String>>> loadMessages() throws Exception {
    Map<String, Map<String, Map<String, String>>> messages = ClassPathResourceUtil
        .readJsonPathMap(I18N_MODULE_MESSAGES_PATH);
    verifyMessages(messages);
    return messages;
  }

  private void importBaseI18nData(
      Map<String, I18nInitializeProperties> data,
      I18nCountriesService countriesService,
      I18nLanguageService languageService,
      I18nRegionService regionService,
      I18nTimeZoneService timeZoneService
  ) {
    data.values().forEach(item -> {
      Countries countries = new Countries();
      BeanUtils.copyProperties(item, countries);
      countriesService.create(countries);
      Set<Language> languages = item.getLanguages();
      if (languages != null && !languages.isEmpty()) {
        languageService.create(languages);
      }
      Set<Region> regions = item.getRegions();
      if (regions != null && !regions.isEmpty()) {
        regionService.create(regions);
      }
      Set<TimeZone> timeZones = item.getTimezones();
      if (timeZones != null && !timeZones.isEmpty()) {
        timeZoneService.create(timeZones);
      }
    });
  }

  private void importMessages(
      Map<String, Map<String, Map<String, String>>> load,
      I18nNamespaceService namespaceService,
      I18nMessageService messageService,
      I18nMessageRepository messageRepository,
      Predicate<String> namespaceFilter
  ) {
    Set<String> namespaceCodesToImport = new HashSet<>();
    for (Map<String, Map<String, String>> namespaceMessages : load.values()) {
      for (String namespaceCode : namespaceMessages.keySet()) {
        if (namespaceFilter.test(namespaceCode)) {
          namespaceCodesToImport.add(namespaceCode);
        }
      }
    }
    if (namespaceCodesToImport.isEmpty()) {
      return;
    }

    Set<String> existingNamespaceCodes = new HashSet<>();
    namespaceService.findAll(Map.of()).stream()
        .map(Namespace::getCode)
        .filter(code -> code != null && !code.isBlank())
        .forEach(existingNamespaceCodes::add);
    Set<String> existingKeys = messageRepository.findExistingKeys(namespaceCodesToImport);

    Set<Namespace> namespaceSet = new HashSet<>();
    Set<Message> messageSet = new HashSet<>();
    for (String locale : load.keySet()) {
      Map<String, Map<String, String>> namespaceMessages = load.get(locale);
      for (String namespaceCode : namespaceMessages.keySet()) {
        if (!namespaceFilter.test(namespaceCode)) {
          continue;
        }
        if (!existingNamespaceCodes.contains(namespaceCode)) {
          namespaceSet.add(new Namespace(namespaceCode, namespaceCode));
          existingNamespaceCodes.add(namespaceCode);
        }
        Map<String, String> messages = namespaceMessages.get(namespaceCode);
        for (String messageKey : messages.keySet()) {
          String compositeKey = locale + ":" + namespaceCode + ":" + messageKey;
          if (existingKeys.contains(compositeKey)) {
            continue;
          }
          Message message = new Message();
          message.setLocale(locale);
          message.setNamespace(namespaceCode);
          message.setCode(messageKey);
          message.setMessage(messages.get(messageKey));
          message.setGlobal(I18N_GLOBAL_NAMESPACES.contains(namespaceCode));
          messageSet.add(message);
        }
      }
    }
    if (!namespaceSet.isEmpty()) {
      namespaceService.create(namespaceSet);
    }
    if (!messageSet.isEmpty()) {
      messageService.create(messageSet);
    }
  }

  private void verifyI18n(Map<String, I18nInitializeProperties> data) {
    if (data == null || data.isEmpty()) {
      throw new IllegalStateException("No i18n country resources found under " + I18N_COUNTRIES_PATH);
    }
  }

  private void verifyMessages(Map<String, Map<String, Map<String, String>>> data) {
    if (data == null || data.isEmpty()) {
      throw new IllegalStateException("No i18n message resources found under " + I18N_MODULE_MESSAGES_PATH);
    }
  }
}
