package org.simplepoint.calculation;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.List;

/** Bounded decimal coercions with locale-independent parsing. */
public final class FormulaValues {
  /** Shared decimal arithmetic precision and rounding policy. */
  public static final MathContext MATH = new MathContext(38, RoundingMode.HALF_UP);

  private FormulaValues() {}

  /** Converts a supported scalar to a bounded decimal. */
  public static BigDecimal number(Object value) {
    if (value == null || "".equals(value)) {
      return BigDecimal.ZERO;
    }
    if (value instanceof Boolean flag) {
      return flag ? BigDecimal.ONE : BigDecimal.ZERO;
    }
    try {
      String text = value.toString();
      if (text.length() > 256 || !(value instanceof Number || value instanceof String)) {
        throw new FormulaException("TYPE");
      }
      BigDecimal result =
          value instanceof BigDecimal decimal ? decimal : new BigDecimal(text.trim());
      if (result.precision() > 128 || Math.abs((long) result.scale()) > 128) {
        throw new FormulaException("NUMBER_RANGE");
      }
      return result;
    } catch (NumberFormatException failure) {
      throw new FormulaException("TYPE");
    }
  }

  /** Converts a supported scalar to locale-independent text. */
  public static String text(Object value) {
    if (value == null) {
      return "";
    }
    if (value instanceof String string) {
      return bounded(string);
    }
    if (value instanceof Boolean flag) {
      return flag ? "TRUE" : "FALSE";
    }
    if (value instanceof Number) {
      return number(value).stripTrailingZeros().toPlainString();
    }
    if (value instanceof TemporalAccessor) {
      return value.toString();
    }
    throw new FormulaException("TYPE");
  }

  /** Converts an explicit boolean or numeric scalar. */
  public static boolean bool(Object value) {
    if (value == null || "".equals(value)) {
      return false;
    }
    if (value instanceof Boolean flag) {
      return flag;
    }
    if (value instanceof Number) {
      return number(value).signum() != 0;
    }
    if ("TRUE".equalsIgnoreCase(String.valueOf(value))) {
      return true;
    }
    if ("FALSE".equalsIgnoreCase(String.valueOf(value))) {
      return false;
    }
    throw new FormulaException("TYPE");
  }

  /** Checks the maximum string result size. */
  public static String bounded(String text) {
    if (text.length() > 65536) {
      throw new FormulaException("VALUE_SIZE");
    }
    return text;
  }

  /** Requires an exact integer inside the supplied range. */
  public static int integer(Object value, int min, int max) {
    try {
      int result = number(value).intValueExact();
      if (result < min || result > max) {
        throw new FormulaException("NUMBER_RANGE");
      }
      return result;
    } catch (ArithmeticException failure) {
      throw new FormulaException("NUMBER_RANGE");
    }
  }

  /** Flattens scalar and single-level list arguments within the collection bound. */
  public static List<Object> flatten(FormulaFunction.Arguments args) {
    var result = new ArrayList<Object>();
    for (int i = 0; i < args.size(); i++) {
      Object value = args.get(i);
      int added = value instanceof List<?> list ? list.size() : 1;
      if (result.size() + added > 10000) {
        throw new FormulaException("VALUE_SIZE");
      }
      if (value instanceof List<?> list) {
        result.addAll(list);
      } else {
        result.add(value);
      }
    }
    return result;
  }
}
