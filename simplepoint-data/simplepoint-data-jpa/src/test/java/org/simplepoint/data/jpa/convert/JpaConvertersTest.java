package org.simplepoint.data.jpa.convert;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JpaConvertersTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  // ---- ObjectMapConvert ----

  @Test
  void objectMapConvert_roundTrip() throws Exception {
    ObjectMapConvert converter = new ObjectMapConvert(objectMapper);
    Map<String, Object> original = new LinkedHashMap<>();
    original.put("key", "value");
    original.put("num", 42);

    String json = converter.convertToDatabaseColumn(original);
    assertThat(json).isNotBlank();

    Map<String, Object> restored = converter.convertToEntityAttribute(json);
    assertThat(restored).containsEntry("key", "value");
    assertThat(restored).containsEntry("num", 42);
  }

  @Test
  void objectMapConvert_nullInput_columnReturnsNullString() {
    ObjectMapConvert converter = new ObjectMapConvert(objectMapper);
    assertThat(converter.convertToDatabaseColumn(null)).isEqualTo("null");
  }

  @Test
  void objectMapConvert_invalidJson_attributeReturnsEmpty() {
    ObjectMapConvert converter = new ObjectMapConvert(objectMapper);
    Map<String, Object> result = converter.convertToEntityAttribute("{invalid-json}");
    assertThat(result).isEmpty();
  }

  @Test
  void objectMapConvert_nullDbValue_throwsOrHandled() {
    ObjectMapConvert converter = new ObjectMapConvert(objectMapper);
    Map<String, Object> result = null;
    try {
      result = converter.convertToEntityAttribute(null);
    } catch (IllegalArgumentException ignored) {
      // Jackson may reject null; that is acceptable behaviour
    }
    if (result != null) {
      assertThat(result).isEmpty();
    }
  }

  // ---- StringSetConvert ----

  @Test
  void stringSetConvert_roundTrip() throws Exception {
    StringSetConvert converter = new StringSetConvert(objectMapper);
    Set<String> original = Set.of("alpha", "beta", "gamma");

    String json = converter.convertToDatabaseColumn(original);
    assertThat(json).isNotBlank();

    Set<String> restored = converter.convertToEntityAttribute(json);
    assertThat(restored).containsExactlyInAnyOrder("alpha", "beta", "gamma");
  }

  @Test
  void stringSetConvert_nullInput_columnReturnsNullString() {
    StringSetConvert converter = new StringSetConvert(objectMapper);
    assertThat(converter.convertToDatabaseColumn(null)).isEqualTo("null");
  }

  @Test
  void stringSetConvert_invalidJson_attributeReturnsEmpty() {
    StringSetConvert converter = new StringSetConvert(objectMapper);
    Set<String> result = converter.convertToEntityAttribute("{not-valid}");
    assertThat(result).isEmpty();
  }

  @Test
  void stringSetConvert_nullDbValue_throwsOrHandled() {
    StringSetConvert converter = new StringSetConvert(objectMapper);
    Set<String> result = null;
    try {
      result = converter.convertToEntityAttribute(null);
    } catch (IllegalArgumentException ignored) {
      // Jackson may reject null; that is acceptable behaviour
    }
    if (result != null) {
      assertThat(result).isEmpty();
    }
  }
}
