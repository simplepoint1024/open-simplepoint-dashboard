package org.simplepoint.plugin.dna.runtime.api.schema;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Bounded, lossless JSON value semantics, independent of presentation and storage dialect. */
public final class LogicalJsonValues {

  private static final int MAX_LENGTH = 1_048_576;
  /** Native serializers may add whitespace to a value within the public UTF-16 character budget. */
  public static final int MAX_STORED_TEXT_LENGTH = MAX_LENGTH * 2;

  private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .disable(JsonWriteFeature.WRITE_HEX_UPPER_CASE)
      .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64)
          .maxStringLength(MAX_LENGTH).maxNumberLength(2000).build())
      .build()).enable(com.fasterxml.jackson.core.JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

  private LogicalJsonValues() {
  }

  /** Object order and numeric spelling do not change identity; array order and string content do. */
  public static String canonicalize(final Object value) {
    return canonicalize(value, MAX_LENGTH);
  }

  private static String canonicalize(final Object value, final int sourceLimit) {
    try {
      String result = JSON.writeValueAsString(read(value, sourceLimit));
      if (result.length() > MAX_LENGTH) {
        throw invalid();
      }
      return result;
    } catch (IOException failure) {
      throw invalid();
    }
  }

  /** Removes native database formatting while retaining the same canonical wire size and complexity limits. */
  public static String canonicalizeStored(final String value) {
    return canonicalize(value, MAX_STORED_TEXT_LENGTH);
  }

  /** JSON null, empty containers and whitespace-only JSON strings are missing business values. */
  public static boolean empty(final String value) {
    JsonNode node = read(value, MAX_LENGTH);
    return node.isNull() || node.isContainerNode() && node.isEmpty()
        || node.isTextual() && node.textValue().codePoints().allMatch(character ->
            Character.isSpaceChar(character) || character >= 9 && character <= 13 || character == 0xFEFF);
  }

  /** Exposes exact numeric values for target dialects to reject lossy native JSON encodings. */
  public static List<BigDecimal> numbers(final String value) {
    List<BigDecimal> numbers = new ArrayList<>();
    collectNumbers(read(value, MAX_LENGTH), numbers);
    return List.copyOf(numbers);
  }

  private static void collectNumbers(final JsonNode node, final List<BigDecimal> numbers) {
    if (node.isNumber()) {
      numbers.add(node.decimalValue());
    } else if (node.isContainerNode()) {
      node.forEach(child -> collectNumbers(child, numbers));
    }
  }

  private static JsonNode read(final Object value, final int sourceLimit) {
    try {
      String text = value instanceof String string ? string : JSON.writeValueAsString(value);
      if (text.length() > sourceLimit) {
        throw invalid();
      }
      try (JsonParser parser = JSON.createParser(text)) {
        parser.nextToken();
        JsonNode result = readNode(parser, 0, new int[] {0});
        if (parser.nextToken() != null) {
          throw invalid();
        }
        return result;
      }
    } catch (IOException failure) {
      throw invalid();
    }
  }

  private static JsonNode readNode(final JsonParser parser, final int depth, final int[] nodes) throws IOException {
    if (depth > 64 || ++nodes[0] > 10_000 || parser.currentToken() == null) {
      throw invalid();
    }
    return switch (parser.currentToken()) {
      case START_OBJECT -> {
        Map<String, JsonNode> sorted = new TreeMap<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
          if (parser.currentToken() != JsonToken.FIELD_NAME) {
            throw invalid();
          }
          String key = unicode(parser.currentName());
          parser.nextToken();
          sorted.put(key, readNode(parser, depth + 1, nodes));
        }
        yield JSON.createObjectNode().setAll(sorted);
      }
      case START_ARRAY -> {
        var array = JSON.createArrayNode();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
          array.add(readNode(parser, depth + 1, nodes));
        }
        yield array;
      }
      case VALUE_STRING -> JSON.getNodeFactory().textNode(unicode(parser.getText()));
      case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> number(parser.getText());
      case VALUE_TRUE -> JSON.getNodeFactory().booleanNode(true);
      case VALUE_FALSE -> JSON.getNodeFactory().booleanNode(false);
      case VALUE_NULL -> JSON.getNodeFactory().nullNode();
      default -> throw invalid();
    };
  }

  private static JsonNode number(final String text) {
    if (text.length() > 2000) {
      throw invalid();
    }
    int exponentIndex = Math.max(text.indexOf('e'), text.indexOf('E'));
    if (exponentIndex >= 0 && new BigDecimal(text.substring(exponentIndex + 1)).abs().compareTo(BigDecimal.valueOf(1000)) > 0) {
      throw invalid();
    }
    BigDecimal raw = new BigDecimal(text);
    BigDecimal normalized = raw.stripTrailingZeros();
    if (Math.abs((long) raw.scale()) > 1000 || Math.abs((long) normalized.scale()) > 1000
        || normalized.toPlainString().length() > 2000) {
      throw invalid();
    }
    return DecimalNode.valueOf(normalized);
  }

  private static String unicode(final String value) {
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (Character.isHighSurrogate(character)) {
        if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
          throw invalid();
        }
      } else if (Character.isLowSurrogate(character)) {
        throw invalid();
      }
    }
    return value;
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("JSON value is invalid or exceeds its complexity budget");
  }
}
