package org.simplepoint.core.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JsonNodeConverterTest {

  private JsonNodeConverter converter;
  private ObjectMapper mapper;

  @BeforeEach
  void setUp() {
    converter = new JsonNodeConverter();
    mapper = new ObjectMapper();
  }

  // -------- convertToDatabaseColumn --------

  @Test
  void convertToDatabaseColumn_nullInput_returnsNull() {
    assertThat(converter.convertToDatabaseColumn(null)).isNull();
  }

  @Test
  void convertToDatabaseColumn_objectNode_returnsJsonString() throws Exception {
    JsonNode node = mapper.readTree("{\"key\":\"value\"}");
    String result = converter.convertToDatabaseColumn(node);
    assertThat(result).isEqualTo("{\"key\":\"value\"}");
  }

  @Test
  void convertToDatabaseColumn_arrayNode_returnsJsonArray() throws Exception {
    JsonNode node = mapper.readTree("[1,2,3]");
    String result = converter.convertToDatabaseColumn(node);
    assertThat(result).isEqualTo("[1,2,3]");
  }

  @Test
  void convertToDatabaseColumn_scalarNode_returnsJsonString() throws Exception {
    JsonNode node = mapper.readTree("\"hello\"");
    String result = converter.convertToDatabaseColumn(node);
    assertThat(result).isEqualTo("\"hello\"");
  }

  // -------- convertToEntityAttribute --------

  @Test
  void convertToEntityAttribute_nullInput_returnsNull() {
    assertThat(converter.convertToEntityAttribute(null)).isNull();
  }

  @Test
  void convertToEntityAttribute_emptyString_returnsNull() {
    assertThat(converter.convertToEntityAttribute("")).isNull();
  }

  @Test
  void convertToEntityAttribute_validJson_returnsJsonNode() throws Exception {
    JsonNode result = converter.convertToEntityAttribute("{\"key\":\"value\"}");
    assertThat(result).isNotNull();
    assertThat(result.get("key").asText()).isEqualTo("value");
  }

  @Test
  void convertToEntityAttribute_jsonArray_returnsArrayNode() {
    JsonNode result = converter.convertToEntityAttribute("[1,2,3]");
    assertThat(result).isNotNull();
    assertThat(result.isArray()).isTrue();
    assertThat(result.size()).isEqualTo(3);
  }

  @Test
  void convertToEntityAttribute_invalidJson_throwsIllegalArgumentException() {
    assertThatThrownBy(() -> converter.convertToEntityAttribute("not-json"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Failed to deserialize JsonNode");
  }

  // -------- round-trip --------

  @Test
  void roundTrip_objectNode_preservesData() throws Exception {
    JsonNode original = mapper.readTree("{\"name\":\"Alice\",\"age\":30}");
    String dbColumn = converter.convertToDatabaseColumn(original);
    JsonNode restored = converter.convertToEntityAttribute(dbColumn);
    assertThat(restored).isEqualTo(original);
  }
}
