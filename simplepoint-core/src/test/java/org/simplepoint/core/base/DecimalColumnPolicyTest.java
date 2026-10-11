package org.simplepoint.core.base;

import static org.junit.jupiter.api.Assertions.*;
import jakarta.persistence.Column;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class DecimalColumnPolicyTest {
  static class Price { @Column(precision = 19, scale = 8) BigDecimal amount; }
  static class PackagePrice { @Column(precision = 19, scale = 2) BigDecimal amount; }

  @Test
  void validationAcceptsExactBoundariesAndUnnecessaryZeroScaleWithoutChangingTheOriginal() {
    var price = new Price(); price.amount = new BigDecimal("99999999999.99999999");
    DecimalColumnPolicy.validate(price); assertEquals("99999999999.99999999", price.amount.toPlainString());
    price.amount = new BigDecimal("0.1000000000"); DecimalColumnPolicy.validate(price);
    assertEquals(10, price.amount.scale());
    var pkg = new PackagePrice(); pkg.amount = new BigDecimal("99999999999999999.99"); DecimalColumnPolicy.validate(pkg);
  }
  @Test
  void validationRejectsRealRoundingOverflowAndHugeExponentsBeforePersistence() {
    var price = new Price();
    for (String invalid : new String[]{"100000000000", "0.000000001", "1E+100000000", "1E-100000000"}) {
      price.amount = new BigDecimal(invalid); assertThrows(IllegalArgumentException.class, () -> DecimalColumnPolicy.validate(price));
    }
    var pkg = new PackagePrice(); pkg.amount = new BigDecimal("0.001");
    assertThrows(IllegalArgumentException.class, () -> DecimalColumnPolicy.validate(pkg));
  }
}
