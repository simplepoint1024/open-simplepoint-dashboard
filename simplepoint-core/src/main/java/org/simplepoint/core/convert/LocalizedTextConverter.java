package org.simplepoint.core.convert;

import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.simplepoint.api.locale.LocalizedText;

/** Stores the value in its owning aggregate transaction, without a remote translation write. */
@Converter
public class LocalizedTextConverter implements AttributeConverter<LocalizedText, String> {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Override
  public String convertToDatabaseColumn(LocalizedText value) {
    if (value == null) return null;
    try { return MAPPER.writeValueAsString(value); }
    catch (Exception e) { throw new IllegalArgumentException("Cannot serialize localized text", e); }
  }

  @Override
  public LocalizedText convertToEntityAttribute(String value) {
    if (value == null) return null;
    try { return MAPPER.readValue(value, LocalizedText.class); }
    catch (Exception e) { throw new IllegalArgumentException("Cannot deserialize localized text", e); }
  }
}
