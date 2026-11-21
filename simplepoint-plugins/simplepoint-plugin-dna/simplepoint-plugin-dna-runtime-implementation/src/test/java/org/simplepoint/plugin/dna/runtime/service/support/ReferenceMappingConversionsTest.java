package org.simplepoint.plugin.dna.runtime.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceConversion;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;

class ReferenceMappingConversionsTest {
  @Test
  void exactIntegersAndMoneyNeverPassThroughFloatingPoint() {
    assertThat(
            convert(
                RecordReferenceConversion.INTEGER,
                LogicalFieldType.STRING,
                LogicalFieldType.INT64,
                "9007199254740993"))
        .isEqualTo(new LogicalValue(LogicalValueKind.INTEGER, "9007199254740993"));
    assertThat(
            convert(
                RecordReferenceConversion.DECIMAL,
                LogicalFieldType.STRING,
                LogicalFieldType.DECIMAL,
                "9007199254740993.0100"))
        .isEqualTo(new LogicalValue(LogicalValueKind.DECIMAL, "9007199254740993.01"));
    assertThat(
            convert(
                RecordReferenceConversion.INTEGER,
                LogicalFieldType.DECIMAL,
                LogicalFieldType.INT64,
                "42.00"))
        .isEqualTo(new LogicalValue(LogicalValueKind.INTEGER, "42"));
  }

  @Test
  void narrowingRejectsFractionOverflowAndRoundingInsteadOfChangingBusinessValues() {
    reject(
        () ->
            convert(
                RecordReferenceConversion.INTEGER,
                LogicalFieldType.STRING,
                LogicalFieldType.INT64,
                "9223372036854775808"));
    reject(
        () ->
            convert(
                RecordReferenceConversion.INTEGER,
                LogicalFieldType.STRING,
                LogicalFieldType.INT32,
                "2147483648"));
    reject(
        () ->
            convert(
                RecordReferenceConversion.INTEGER,
                LogicalFieldType.DECIMAL,
                LogicalFieldType.INT32,
                "3.14"));
    reject(
        () ->
            convert(
                RecordReferenceConversion.DECIMAL,
                LogicalFieldType.STRING,
                LogicalFieldType.DECIMAL,
                "3.141"));
    reject(
        () ->
            convert(
                RecordReferenceConversion.IDENTITY,
                LogicalFieldType.INT64,
                LogicalFieldType.INT32,
                "42"));
    reject(
        () ->
            convert(
                RecordReferenceConversion.INTEGER,
                LogicalFieldType.DATE,
                LogicalFieldType.INT64,
                "2026-10-29"));
  }

  @Test
  void sourceSchemaIsValidatedEvenWhenTheDestinationIsOnlyText() {
    reject(
        () ->
            convert(
                RecordReferenceConversion.STRING,
                LogicalFieldType.INT32,
                LogicalFieldType.STRING,
                "2147483648"));
    reject(
        () ->
            convert(
                RecordReferenceConversion.STRING,
                LogicalFieldType.BOOLEAN,
                LogicalFieldType.STRING,
                "true"));
    reject(
        () ->
            convert(
                RecordReferenceConversion.STRING,
                LogicalFieldType.DECIMAL,
                LogicalFieldType.STRING,
                0.1d));
    assertThat(
            convert(
                    RecordReferenceConversion.STRING,
                    LogicalFieldType.DECIMAL,
                    LogicalFieldType.STRING,
                    new BigDecimal("0.10"))
                .canonicalValue())
        .isEqualTo("0.10");
    assertThat(
            convert(
                    RecordReferenceConversion.STRING,
                    LogicalFieldType.BOOLEAN,
                    LogicalFieldType.STRING,
                    true)
                .canonicalValue())
        .isEqualTo("true");
  }

  @Test
  void unicodeLengthsUseCodePointsAndPreserveTextWithoutTrimming() {
    var destination =
        new LogicalFieldDefinition(
            "dest", "dest", "Dest", LogicalFieldType.STRING, true, 3, null, null, null);
    assertThat(
            ReferenceMappingConversions.convert(
                    RecordReferenceConversion.STRING,
                    field(LogicalFieldType.TEXT),
                    destination,
                    "中日😀")
                .canonicalValue())
        .isEqualTo("中日😀");
    reject(
        () ->
            ReferenceMappingConversions.convert(
                RecordReferenceConversion.STRING,
                field(LogicalFieldType.TEXT),
                destination,
                "中日😀x"));
    assertThat(
            convert(
                    RecordReferenceConversion.IDENTITY,
                    LogicalFieldType.STRING,
                    LogicalFieldType.STRING,
                    " x ")
                .canonicalValue())
        .isEqualTo(" x ");
  }

  @Test
  void civilTimeIsNotShiftedWhileOffsetBearingInstantsUseTheOrdinaryCanonicalContract() {
    assertThat(
            convert(
                    RecordReferenceConversion.STRING,
                    LogicalFieldType.DATE,
                    LogicalFieldType.STRING,
                    "2026-10-29")
                .canonicalValue())
        .isEqualTo("2026-10-29");
    assertThat(
            convert(
                    RecordReferenceConversion.STRING,
                    LogicalFieldType.TIME,
                    LogicalFieldType.STRING,
                    "08:09:10.123456")
                .canonicalValue())
        .isEqualTo("08:09:10.123456");
    assertThat(
            convert(
                    RecordReferenceConversion.STRING,
                    LogicalFieldType.DATETIME,
                    LogicalFieldType.STRING,
                    "2026-10-29T08:00:00+08:00")
                .canonicalValue())
        .isEqualTo("2026-10-29T00:00:00Z");
    reject(
        () ->
            convert(
                RecordReferenceConversion.STRING,
                LogicalFieldType.DATETIME,
                LogicalFieldType.STRING,
                "2026-10-29T08:00:00"));
    reject(
        () ->
            convert(
                RecordReferenceConversion.STRING,
                LogicalFieldType.TIME,
                LogicalFieldType.STRING,
                "08:09:10.123456789"));
  }

  @Test
  void nullableConversionStillRequiresCompatibleTypesAndDestinationNullability() {
    assertThat(
            convert(
                RecordReferenceConversion.IDENTITY,
                LogicalFieldType.STRING,
                LogicalFieldType.STRING,
                null))
        .isEqualTo(LogicalValue.nullValue());
    reject(
        () ->
            convert(
                RecordReferenceConversion.INTEGER,
                LogicalFieldType.DATE,
                LogicalFieldType.INT32,
                null));
    var required =
        new LogicalFieldDefinition(
            "dest", "dest", "Dest", LogicalFieldType.STRING, false, 100, null, null, null);
    reject(
        () ->
            ReferenceMappingConversions.convert(
                RecordReferenceConversion.IDENTITY,
                field(LogicalFieldType.STRING),
                required,
                null));
  }

  @Test
  void hugeNumbersAndLocaleFormattedTextAreBoundedBeforeParsingOrScaleExpansion() {
    for (String value :
        List.of("1e2147483647", "1e-2147483647", "9".repeat(129), " 12 ", "1,000", "NaN", "∞")) {
      reject(
          () ->
              convert(
                  RecordReferenceConversion.DECIMAL,
                  LogicalFieldType.STRING,
                  LogicalFieldType.DECIMAL,
                  value));
    }
    for (Object value :
        List.of(
            new BigDecimal(BigInteger.ONE, Integer.MIN_VALUE),
            BigInteger.ONE.shiftLeft(10000),
            Double.NaN,
            Float.POSITIVE_INFINITY)) {
      reject(
          () ->
              convert(
                  RecordReferenceConversion.STRING,
                  LogicalFieldType.DECIMAL,
                  LogicalFieldType.STRING,
                  value));
    }
    reject(
        () ->
            convert(
                RecordReferenceConversion.STRING,
                LogicalFieldType.TEXT,
                LogicalFieldType.TEXT,
                "x".repeat(65_536)));
  }

  @Test
  void conversionErrorsDoNotExposeInputEvenInCauseChains() {
    assertThatThrownBy(
            () ->
                convert(
                    RecordReferenceConversion.DECIMAL,
                    LogicalFieldType.STRING,
                    LogicalFieldType.DECIMAL,
                    "private-address-secret"))
        .isInstanceOfSatisfying(
            DnaRuntimeException.class,
            failure -> {
              assertThat(failure.getMessage()).doesNotContain("private-address-secret");
              assertThat(failure.getCause()).isNull();
              assertThat(failure.getSuppressed()).isEmpty();
            });
    reject(
        () ->
            convert(
                RecordReferenceConversion.STRING,
                LogicalFieldType.JSON,
                LogicalFieldType.STRING,
                "{}"));
    reject(
        () ->
            convert(
                RecordReferenceConversion.STRING,
                LogicalFieldType.BINARY,
                LogicalFieldType.STRING,
                "c2VjcmV0"));
  }

  private static LogicalValue convert(
      RecordReferenceConversion conversion,
      LogicalFieldType source,
      LogicalFieldType destination,
      Object value) {
    return ReferenceMappingConversions.convert(
        conversion, field(source), field(destination), value);
  }

  private static LogicalFieldDefinition field(LogicalFieldType type) {
    return new LogicalFieldDefinition(
        "value",
        "value",
        "Value",
        type,
        true,
        type == LogicalFieldType.STRING ? 256 : null,
        type == LogicalFieldType.DECIMAL ? 38 : null,
        type == LogicalFieldType.DECIMAL ? 2 : null,
        null);
  }

  private static void reject(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            DnaRuntimeException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(DnaRuntimeErrorCode.VALUE_CONVERSION_FAILED));
  }
}
