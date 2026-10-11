package org.simplepoint.core.jackson;

import java.math.BigDecimal;

/** Exact JSON text; scientific fallback bounds expansion for arbitrary non-domain decimal values. */
final class DecimalWireValue {
  private DecimalWireValue() {}

  static String text(BigDecimal value) {
    long precision = value.precision(), scale = value.scale();
    long plainLength = scale < 0 ? precision - scale : Math.max(precision + 1, scale + 2);
    return plainLength <= 2000 ? value.toPlainString() : value.toString();
  }
}
