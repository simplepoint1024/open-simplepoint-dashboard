package org.simplepoint.api.schema;

import java.util.List;
import java.util.Map;

/** Versioned schema contract also usable by projection schemas without a reflective generator. */
public final class LocalizedFieldSchema {
  private LocalizedFieldSchema() {}

  public static Map<String, Object> describe(LocalizedField field) {
    if (field.maxLength() < 1 || field.maxLanguages() < 1 || field.maxLanguages() > 64)
      throw new IllegalArgumentException("Invalid localized field declaration");
    var text = Map.of("type", "string", "minLength", 1, "maxLength", field.maxLength(), "pattern", "\\S");
    return Map.of(
        "type", "object", "additionalProperties", false,
        "required", List.of("defaultValue", "defaultLocale", "translations"),
        "properties", Map.of(
            "defaultValue", text,
            "defaultLocale", Map.of("type", "string", "minLength", 2, "maxLength", 64),
            "translations", Map.of("type", "object", "maxProperties", field.maxLanguages(), "additionalProperties", text)),
        "x-localized", Map.of("version", 1, "maxLength", field.maxLength(), "maxLanguages", field.maxLanguages(),
            "multiline", field.multiline(), "tenantOverride", field.tenantOverride()),
        "x-ui", Map.of("ui:field", "LocalizedText"));
  }
}
