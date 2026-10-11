package org.simplepoint.plugin.dna.runtime.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;

class StandardLogicalValueConverterTest {

  private final StandardLogicalValueConverter converter =
      new StandardLogicalValueConverter(new ObjectMapper());

  @Test
  void canonicalizesJsonObjectKeysAndUtcDateTime() {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("z", 1.0);
    json.put("a", Map.of("y", 2, "b", 3));

    LogicalValue jsonValue = converter.normalize(field(LogicalFieldType.JSON, true), json);
    LogicalValue dateTime = converter.normalize(
        field(LogicalFieldType.DATETIME, false),
        OffsetDateTime.parse("2026-09-29T10:15:30+08:00")
    );

    assertThat(jsonValue.kind()).isEqualTo(LogicalValueKind.JSON);
    assertThat(jsonValue.canonicalValue()).isEqualTo("{\"a\":{\"b\":3,\"y\":2},\"z\":1}");
    assertThat(dateTime.canonicalValue()).isEqualTo("2026-09-29T02:15:30Z");
  }

  @Test
  void enforcesRequiredNullAndLosslessDecimalScale() {
    assertFailure(
        () -> converter.normalize(field(LogicalFieldType.INT64, false), null)
    );
    assertFailure(() -> converter.normalize(decimalField(), "12.345"));

    assertThat(converter.normalize(decimalField(), "12.3").canonicalValue())
        .isEqualTo("12.30");
  }

  @Test
  void rejectsNumericOverflowWithoutIncludingTheInputInTheError() {
    assertThatThrownBy(() -> converter.normalize(
        field(LogicalFieldType.INT32, false),
        "9223372036854775807"
    )).isInstanceOfSatisfying(DnaRuntimeException.class, exception -> {
      assertThat(exception.errorCode())
          .isEqualTo(DnaRuntimeErrorCode.VALUE_CONVERSION_FAILED);
      assertThat(exception.getMessage()).doesNotContain("9223372036854775807");
    });
  }

  private static void assertFailure(final Runnable operation) {
    assertThatThrownBy(operation::run)
        .isInstanceOfSatisfying(DnaRuntimeException.class, exception ->
            assertThat(exception.errorCode())
                .isEqualTo(DnaRuntimeErrorCode.VALUE_CONVERSION_FAILED));
  }

  private static LogicalFieldDefinition field(
      final LogicalFieldType type,
      final boolean nullable
  ) {
    return new LogicalFieldDefinition(
        "field-id",
        "amount",
        "Amount",
        type,
        nullable,
        type == LogicalFieldType.STRING ? 128 : null,
        null,
        null,
        null
    );
  }

  private static LogicalFieldDefinition decimalField() {
    return new LogicalFieldDefinition(
        "field-id",
        "amount",
        "Amount",
        LogicalFieldType.DECIMAL,
        false,
        null,
        8,
        2,
        null
    );
  }
}
