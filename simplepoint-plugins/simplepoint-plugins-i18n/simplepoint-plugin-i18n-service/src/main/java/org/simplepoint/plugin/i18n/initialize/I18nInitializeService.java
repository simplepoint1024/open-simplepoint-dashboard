package org.simplepoint.plugin.i18n.initialize;

import jakarta.transaction.Transactional;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.core.entity.Message;
import org.simplepoint.core.locale.I18nMessageService;
import org.simplepoint.core.utils.ClassPathResourceUtil;
import org.simplepoint.data.initialize.DataInitializeManager;
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
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Service to initialize internationalization (i18n) data on application startup.
 */
@Slf4j
@Component
public class I18nInitializeService implements ApplicationRunner {
  private static final Set<String> I18N_GLOBAL_NAMESPACES = Set.of(
      "common",
      "errors",
      "messages",
      "settings"
  );
  private static final String I18N_INIT_MODULE = "i18n-initialize";

  private static final String I18N_INIT_MSG_MODULE = "i18n-initialize-messages";

  private final DataInitializeManager initializeManager;

  private final I18nCountriesService countriesService;

  private final I18nLanguageService languageService;

  private final I18nRegionService regionService;

  private final I18nTimeZoneService timeZoneService;

  private final I18nNamespaceService namespaceService;

  private final I18nMessageService messageService;

  /**
   * Constructor for I18nInitializeService.
   *
   * @param initializeManager the DataInitializeManager to be used
   */
  public I18nInitializeService(DataInitializeManager initializeManager, I18nCountriesService countriesService, I18nLanguageService languageService,
                               I18nRegionService regionService, I18nTimeZoneService timeZoneService, I18nNamespaceService namespaceService,
                               I18nMessageService messageService) {
    this.initializeManager = initializeManager;
    this.countriesService = countriesService;
    this.languageService = languageService;
    this.regionService = regionService;
    this.timeZoneService = timeZoneService;
    this.namespaceService = namespaceService;
    this.messageService = messageService;
  }

  /**
   * Initialize internationalization data from JSON files.
   */
  @Transactional(rollbackOn = Exception.class)
  public void initI18n() {
    initializeManager.execute(I18N_INIT_MODULE, () -> {
      Map<String, I18nInitializeProperties> data = ClassPathResourceUtil.readJson("/i18n/countries/", I18nInitializeProperties.class);
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

  private void verifyI18n(Map<String, I18nInitializeProperties> data) {

  }

  /**
   * Initialize internationalization messages from JSON files.
   */
  private void initI18nMessages() {
    initializeManager.execute(I18N_INIT_MSG_MODULE, () -> {
      Map<String, Map<String, Map<String, String>>> load = ClassPathResourceUtil.readJsonPathMap("/i18n/messages/");
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


  /**
   * Run method to initialize i18n data and messages on application startup.
   *
   * @param args application arguments
   */
  @Override
  public void run(ApplicationArguments args) {
    this.initI18n();
    this.initI18nMessages();
  }
}
