package org.simplepoint.plugin.dna.runtime.api.schema;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Set;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceConversion;

/** Exact, bounded mapping conversions using the ordinary record normalization contract. */
public final class ReferenceMappingConversions {
  private static final CanonicalLogicalValueConverter VALUES = new CanonicalLogicalValueConverter();
  private static final int MAX_TEXT = 65_535;
  private static final int MAX_NUMBER = 128;

  private ReferenceMappingConversions() {}

  /**
   * Converts one authorized value. Caller owns null policy, source version, permissions and
   * provenance. No input value is included in the public diagnostic or its cause chain.
   */
  public static LogicalValue convert(
      RecordReferenceConversion conversion,
      LogicalFieldDefinition source,
      LogicalFieldDefinition destination,
      Object value) {
    if (conversion == null
        || source == null
        || destination == null
        || !conversion.supports(source.type(), destination.type())) {
      throw invalid();
    }
    try {
      bounded(value, numeric(source.type()));
      var normalized = VALUES.normalize(source, value);
      if (value == null) {
        return VALUES.normalize(destination, null);
      }
      Object candidate = LogicalValueInputs.fromCanonical(normalized);
      switch (conversion) {
        case IDENTITY -> {}
        case STRING -> candidate = normalized.canonicalValue();
        case INTEGER, DECIMAL -> {
          bounded(normalized.canonicalValue(), true);
          var number = new BigDecimal(normalized.canonicalValue());
          candidate =
              conversion == RecordReferenceConversion.DECIMAL
                  ? number.setScale(destination.scale(), RoundingMode.UNNECESSARY)
                  : number.toBigIntegerExact();
        }
        default -> throw invalid();
      }
      return VALUES.normalize(destination, candidate);
    } catch (RuntimeException failure) {
      // Value normalizers may retain parser causes containing input; this boundary never does.
      throw invalid();
    }
  }

  private static void bounded(Object value, boolean numeric) {
    if (value instanceof Double || value instanceof Float) {
      throw invalid();
    }
    if (value instanceof BigDecimal number
        && (number.precision() > MAX_NUMBER || Math.abs((long) number.scale()) > MAX_NUMBER)) {
      throw invalid();
    }
    if (value instanceof BigInteger number && number.bitLength() > 426) {
      throw invalid();
    }
    if (value instanceof String text) {
      if (text.length() > MAX_TEXT
          || numeric
              && (text.length() > MAX_NUMBER || !text.matches("[+-]?[0-9]+(?:\\.[0-9]+)?"))) {
        throw invalid();
      }
    }
  }

  private static boolean numeric(LogicalFieldType type) {
    return Set.of(LogicalFieldType.INT32, LogicalFieldType.INT64, LogicalFieldType.DECIMAL)
        .contains(type);
  }

  private static DnaRuntimeException invalid() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.VALUE_CONVERSION_FAILED,
        "Reference value cannot be converted without loss under the declared mapping");
  }
}
