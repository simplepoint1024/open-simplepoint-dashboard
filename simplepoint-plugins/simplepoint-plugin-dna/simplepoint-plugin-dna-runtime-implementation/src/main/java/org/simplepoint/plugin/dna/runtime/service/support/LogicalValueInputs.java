package org.simplepoint.plugin.dna.runtime.service.support;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;

/** Shared canonical-default input conversion for schema validation, Record writes and calculation. */
public final class LogicalValueInputs {
  private LogicalValueInputs() {
  }

  /** Restores a typed input without parsing numbers through floating point. */
  public static Object fromCanonical(final LogicalValue value) {
    if (value == null) {
      return null;
    }
    return switch (value.kind()) {
      case NULL -> null;
      case BOOLEAN -> parseBoolean(value.canonicalValue());
      case INTEGER -> new BigInteger(value.canonicalValue());
      case DECIMAL -> new BigDecimal(value.canonicalValue());
      case STRING, DATE, TIME, DATETIME, UUID, JSON, BINARY -> value.canonicalValue();
    };
  }

  private static boolean parseBoolean(final String value) {
    if (!"true".equals(value) && !"false".equals(value)) {
      throw new IllegalArgumentException("Boolean default must be true or false");
    }
    return Boolean.parseBoolean(value);
  }
}
