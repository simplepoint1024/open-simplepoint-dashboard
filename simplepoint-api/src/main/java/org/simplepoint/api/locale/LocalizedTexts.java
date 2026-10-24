package org.simplepoint.api.locale;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Pure validation/resolution shared by domain adapters; it performs no IO or persistence. */
public final class LocalizedTexts {
  private LocalizedTexts() {}

  public static String locale(String value) {
    if (value == null || value.length() > 64 || value.isBlank()) throw invalid();
    try {
      var locale = new Locale.Builder().setLanguageTag(value.replace('_', '-')).build();
      if (locale.getLanguage().isBlank() || "und".equals(locale.getLanguage())
          || !locale.getExtensionKeys().isEmpty()) throw invalid();
      return locale.toLanguageTag();
    } catch (java.util.IllformedLocaleException e) {
      throw invalid();
    }
  }

  public static LocalizedText validate(LocalizedText value, int maxLength, int maxLanguages) {
    if (value == null || value.translations().size() > maxLanguages) throw invalid();
    var translations = new LinkedHashMap<String, String>();
    value.translations().forEach((language, text) -> {
      if (translations.put(locale(language), text(text, maxLength)) != null) throw invalid();
    });
    return new LocalizedText(text(value.defaultValue(), maxLength), locale(value.defaultLocale()), translations);
  }

  /** Locale-specific overrides precede language-only overrides; defaults are a separate fallback. */
  public static String override(LocalizedText value, String language) {
    if (value == null) return null;
    String canonical = locale(language);
    String exact = value.translations().get(canonical);
    return exact != null ? exact : value.translations().get(Locale.forLanguageTag(canonical).getLanguage());
  }

  private static String text(String value, int maxLength) {
    if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > maxLength)
      throw invalid();
    return value;
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("form.localized.invalid");
  }
}
