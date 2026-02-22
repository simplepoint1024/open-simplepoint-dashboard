package org.simplepoint.plugin.i18n.initialize;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
import org.simplepoint.plugin.i18n.api.service.I18nCountriesService;
import org.simplepoint.plugin.i18n.api.service.I18nLanguageService;
import org.simplepoint.plugin.i18n.api.service.I18nNamespaceService;
import org.simplepoint.plugin.i18n.api.service.I18nRegionService;
import org.simplepoint.plugin.i18n.api.service.I18nTimeZoneService;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * I18nAutoRegistrationInitializer is responsible for automatically registering internationalization (i18n) data during application startup.
 *
 * <p>This class defines two DataInitRegister beans: one for initializing i18n data such as countries, languages, regions, and time zones, and another for initializing i18n messages. The initialization tasks read data from JSON files located in the classpath and populate the corresponding services with the data.</p>
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
      Map<String, I18nInitializeProperties> data = ClassPathResourceUtil
          .readJson("/i18n/countries/", I18nInitializeProperties.class);
      verifyI18n(data);
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
      I18nMessageService messageService
  ) {
    return () -> new InitTask(I18N_INIT_MSG_MODULE, () -> {
      Map<String, Map<String, Map<String, String>>> load = ClassPathResourceUtil
          .readJsonPathMap("/i18n/messages/");
      Set<String> regNsCodes = new HashSet<>();
      Set<Namespace> namespaceSet = new HashSet<>();
      Set<Message> messageSet = new HashSet<>();
      for (String locale : load.keySet()) {
        Map<String, Map<String, String>> namespaceMessages = load.get(locale);
        Set<String> namespaceCodes = namespaceMessages.keySet();
        for (String namespaceCode : namespaceCodes) {
          if (!regNsCodes.contains(namespaceCode)) {
            namespaceSet.add(new Namespace(namespaceCode, namespaceCode));
            regNsCodes.add(namespaceCode);
          }
          Map<String, String> messages = namespaceMessages.get(namespaceCode);
          for (String messageKey : messages.keySet()) {
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
    });
  }

  private void verifyI18n(Map<String, I18nInitializeProperties> data) {

  }

}
