package org.simplepoint.core.convert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA AttributeConverter to convert JsonNode to String and vice versa for database storage.
 */
@Converter
public class JsonNodeConverter implements AttributeConverter<JsonNode, String> {

  private static final ObjectMapper mapper = new ObjectMapper();

  @Override
  public String convertToDatabaseColumn(JsonNode attribute) {
    if (attribute == null) {
      return null;
    }
    try {
      return mapper.writeValueAsString(attribute);
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to serialize JsonNode", e);
    }
  }

  @Override
  public JsonNode convertToEntityAttribute(String dbData) {
    if (dbData == null || dbData.isEmpty()) {
      return null;
    }
    try {
      return mapper.readTree(dbData);
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to deserialize JsonNode", e);
    }
  }
}
