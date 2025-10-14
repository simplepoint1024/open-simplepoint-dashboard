package org.simplepoint.data.jpa.convert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Collections;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;


/**
 * Attribute converter for converting a {@link Set} of {@code String} values
 * to a JSON {@code String} representation for database storage.
 *
 * <p>This class ensures that a collection of strings can be persisted as a single
 * JSON-formatted string column in the database and converted back upon retrieval.</p>
 *
 * @author JinxuLiu
 * @since 1.0
 */
@Slf4j
@Converter(autoApply = true)
public class StringSetConvert implements AttributeConverter<Set<String>, String> {

  private final ObjectMapper objectMapper;

  /**
   * Constructs a {@code StringSetConvert} instance with the specified {@link ObjectMapper}.
   *
   * @param objectMapper the {@link ObjectMapper} used for JSON serialization and deserialization
   */
  public StringSetConvert(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * Converts a {@link Set} of {@code String} values into a JSON {@code String} for database storage.
   *
   * @param stringSet the {@link Set} of strings to be converted
   * @return a JSON-formatted {@code String}, or {@code null} if conversion fails
   */
  @Override
  public String convertToDatabaseColumn(Set<String> stringSet) {
    try {
      return objectMapper.writeValueAsString(stringSet);
    } catch (JsonProcessingException e) {
      log.error("Error converting Set<String> to JSON string: {}", e.getMessage(), e);
    }
    return null;
  }

  /**
   * Converts a JSON {@code String} representation back into a {@link Set} of {@code String} values.
   *
   * @param jsonString the JSON-formatted string to be converted
   * @return a {@link Set} of strings, or an empty set if conversion fails
   */
  @Override
  public Set<String> convertToEntityAttribute(String jsonString) {
    try {
      return objectMapper.readValue(jsonString, new TypeReference<>() {
      });
    } catch (JsonProcessingException e) {
      log.error("Error converting JSON string to Set<String>: {}", e.getMessage(), e);
    }
    return Collections.emptySet();
  }
}