package org.simplepoint.api.locale;

import java.util.Map;

/** An aggregate-owned value. Inherited messages and tenant identity are never writable input. */
public record LocalizedText(String defaultValue, String defaultLocale, Map<String, String> translations) {
  public LocalizedText {
    translations = translations == null ? Map.of() : Map.copyOf(translations);
  }
}
