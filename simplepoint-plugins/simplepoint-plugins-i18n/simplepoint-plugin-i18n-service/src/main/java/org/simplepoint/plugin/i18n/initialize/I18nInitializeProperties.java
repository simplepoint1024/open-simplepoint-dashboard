package org.simplepoint.plugin.i18n.initialize;

import java.util.Map;
import java.util.Set;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.core.utils.ClassPathResourceUtil;
import org.simplepoint.plugin.i18n.api.entity.Countries;
import org.simplepoint.plugin.i18n.api.entity.Language;
import org.simplepoint.plugin.i18n.api.entity.Region;
import org.simplepoint.plugin.i18n.api.entity.TimeZone;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Utility class to load i18n initialization properties from JSON files.
 */
@Data
@Slf4j
@Component
@EqualsAndHashCode(callSuper = true)
public class I18nInitializeProperties extends Countries implements InitializingBean {
  private Set<Region> regions;

  private Set<TimeZone> timeZones;

  private Set<Language> languages;

  @Override
  public void afterPropertiesSet() throws Exception {
    try {
      Map<String, I18nInitializeProperties> load = ClassPathResourceUtil.readJson("/i18n/countries/", I18nInitializeProperties.class);

    } catch (Exception e) {
      log.info("skip i18n initialize", e);
    }
    try {
      Map<String, Map<String, Map<String, String>>> stringMapMap = ClassPathResourceUtil.readJsonPathMap("/i18n/messages/");
      System.out.println(stringMapMap);
    } catch (Exception e) {
      log.info("skip i18n messages initialize", e);
    }
  }
}
