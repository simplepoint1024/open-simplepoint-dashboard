package org.simplepoint.data.jpa.convert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Collections;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * A JPA attribute converter for converting a Map String, Object to a JSON string.
 * and vice versa using Jackson's ObjectMapper.
 * Automatically applied to relevant fields in entity classes.
 */
@Slf4j
@Converter(autoApply = true)
public class ObjectMapConvert implements AttributeConverter<Map<String, Object>, String> {

  /**
   * The ObjectMapper instance used for JSON serialization and deserialization.
   */
  private final ObjectMapper objectMapper;

  /**
   * Constructs a new ObjectMapConvert with a specified ObjectMapper.
   *
   * @param objectMapper the ObjectMapper instance to use
   */
  public ObjectMapConvert(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public String convertToDatabaseColumn(Map<String, Object> stringObjectMap) {
    try {
      return objectMapper.writeValueAsString(stringObjectMap);
    } catch (JsonProcessingException e) {
      log.error(e.getMessage(), e);
    }
    return null;
  }

  @Override
  public Map<String, Object> convertToEntityAttribute(String string) {
    try {
      return objectMapper.readValue(string, new TypeReference<>() {
      });
    } catch (JsonProcessingException e) {
      log.error(e.getMessage(), e);
    }
    return Collections.emptyMap();
  }
}
