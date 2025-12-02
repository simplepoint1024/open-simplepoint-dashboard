package org.simplepoint.plugin.i18n.initialize;

import java.util.Set;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.plugin.i18n.api.entity.Countries;
import org.simplepoint.plugin.i18n.api.entity.Language;
import org.simplepoint.plugin.i18n.api.entity.Region;
import org.simplepoint.plugin.i18n.api.entity.TimeZone;

/**
 * Utility class to load i18n initialization properties from JSON files.
 */
@Data
@Slf4j
@EqualsAndHashCode(callSuper = true)
public class I18nInitializeProperties extends Countries {
  private Set<Region> regions;

  private Set<TimeZone> timezones;

  private Set<Language> languages;
}
