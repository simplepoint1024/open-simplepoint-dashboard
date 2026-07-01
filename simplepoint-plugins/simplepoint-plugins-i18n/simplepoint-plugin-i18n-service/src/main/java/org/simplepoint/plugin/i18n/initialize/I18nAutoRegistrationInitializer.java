package org.simplepoint.plugin.i18n.initialize;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.simplepoint.api.data.DataInitRegister;
import org.simplepoint.api.data.InitTask;
import org.simplepoint.core.entity.Message;
import org.simplepoint.core.locale.I18nMessageService;
import org.simplepoint.core.utils.ClassPathResourceUtil;
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
 * <p>This class defines DataInitRegister beans for base i18n data and i18n messages. The
 * initialization tasks read classpath JSON files and populate the corresponding services.
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

  private static final String I18N_INIT_DATA_PERMISSION_MODULE = "i18n-messages-data-permission";

  private static final String I18N_INIT_SUPPLEMENT_MODULE = "i18n-messages-supplement-v1";

  private static final String I18N_BOOTSTRAP_REPAIR_MODULE = "i18n-bootstrap-v2";

  private static final String I18N_COUNTRIES_PATH = "/i18n/countries/";

  private static final String I18N_MESSAGES_PATH = "/i18n/messages/";

  private static final Set<String> DATA_PERMISSION_NAMESPACES = Set.of(
      "data-scopes",
      "field-scopes"
  );

  /**
   * Namespaces that were missing en-US translations or had new fields added after the initial
   * i18n-messages run. Loaded by the supplement init module.
   * Includes data-scopes and field-scopes so this module covers them idempotently even if
   * the data-permission module already ran (existing keys are skipped via pre-filter).
   */
  private static final Set<String> SUPPLEMENT_NAMESPACES = Set.of(
      "users", "roles", "permissions", "menus", "messages", "namespaces",
      "clients", "countries", "languages", "profile", "regions", "settings", "timezones",
      "data-scopes", "field-scopes"
  );

  /**
   * Register a DataInitRegister bean for initializing i18n data.
   *
   * @return a DataInitRegister instance that initializes i18n data
   */
  @Bean
  public DataInitRegister i18nRegister(
      I18nCountriesService countriesService,
      I18nLanguageService languageService,
      I18nRegionService regionService,
      I18nTimeZoneService timeZoneService
  ) {
    return () -> new InitTask(I18N_INIT_MODULE, () -> {
      if (languageService.findAll(Map.of()).isEmpty()) {
        importBaseI18nData(loadBaseI18nData(), countriesService, languageService, regionService, timeZoneService);
      }
    });
  }

  /**
   * Register a DataInitRegister bean for initializing i18n messages.
   *
   * @return a DataInitRegister instance that initializes i18n messages
   */
  @Bean
  public DataInitRegister messagesRegister(
      I18nNamespaceService namespaceService,
      I18nMessageService messageService,
      I18nMessageRepository messageRepository
  ) {
    return () -> new InitTask(I18N_INIT_MSG_MODULE, () ->
        importMessages(loadMessages(), namespaceService, messageService, messageRepository, namespace -> true)
    );
  }

  /**
   * Register a DataInitRegister bean for initializing data-permission-related i18n messages.
   *
   * <p>This runs after the base i18n-messages module and loads the data-scopes and field-scopes
   * namespaces that were added for the data permission feature.
   *
   * @return a DataInitRegister instance that initializes data-permission i18n messages
   */
  @Bean
  public DataInitRegister dataPermissionMessagesRegister(
      I18nNamespaceService namespaceService,
      I18nMessageService messageService,
      I18nMessageRepository messageRepository
  ) {
    return () -> new InitTask(I18N_INIT_DATA_PERMISSION_MODULE, () ->
        importMessages(loadMessages(), namespaceService, messageService, messageRepository, DATA_PERMISSION_NAMESPACES::contains)
    );
  }

  /**
   * Register a DataInitRegister bean for supplementing missing or updated i18n messages.
   *
   * <p>Loads namespaces that were missing en-US translations or had new fields added after the
   * initial i18n-messages run (e.g. users.orgId, roles en-US, permissions en-US, etc.).
   * Also covers data-scopes and field-scopes idempotently: if the data-permission module already
   * inserted some entries, this module skips them to avoid unique-constraint violations.</p>
   *
   * @return a DataInitRegister instance that supplements i18n messages
   */
  @Bean
  public DataInitRegister supplementMessagesRegister(
      I18nNamespaceService namespaceService,
      I18nMessageService messageService,
      I18nMessageRepository messageRepository
  ) {
    return () -> new InitTask(I18N_INIT_SUPPLEMENT_MODULE, () ->
        importMessages(loadMessages(), namespaceService, messageService, messageRepository, SUPPLEMENT_NAMESPACES::contains)
    );
  }

  /**
   * Repairs persisted environments where old i18n initialization was marked done even though
   * resource scanning imported no rows.
   */
  @Bean
  public DataInitRegister bootstrapRepairRegister(
      I18nCountriesService countriesService,
      I18nLanguageService languageService,
      I18nRegionService regionService,
      I18nTimeZoneService timeZoneService,
      I18nNamespaceService namespaceService,
      I18nMessageService messageService,
      I18nMessageRepository messageRepository
  ) {
    return () -> new InitTask(I18N_BOOTSTRAP_REPAIR_MODULE, () -> {
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
        .readJsonPathMap(I18N_MESSAGES_PATH);
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
      throw new IllegalStateException("No i18n message resources found under " + I18N_MESSAGES_PATH);
    }
  }
}
