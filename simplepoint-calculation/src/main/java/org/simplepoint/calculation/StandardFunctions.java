package org.simplepoint.calculation;

import static org.simplepoint.calculation.FormulaValues.MATH;
import static org.simplepoint.calculation.FormulaValues.bool;
import static org.simplepoint.calculation.FormulaValues.bounded;
import static org.simplepoint.calculation.FormulaValues.flatten;
import static org.simplepoint.calculation.FormulaValues.integer;
import static org.simplepoint.calculation.FormulaValues.number;
import static org.simplepoint.calculation.FormulaValues.text;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Built-ins are registered through the same SPI available to independent applications. */
public final class StandardFunctions {
  private StandardFunctions() {}

  /** Returns the built-in functions through the public extension contract. */
  public static List<FormulaFunction> all() {
    var functions = new ArrayList<FormulaFunction>();
    add(
        functions,
        "IF",
        "LOGIC",
        2,
        3,
        "ANY",
        a -> bool(a.get(0)) ? a.get(1) : a.size() == 3 ? a.get(2) : false);
    add(
        functions,
        "IFERROR",
        "LOGIC",
        2,
        2,
        "ANY",
        a -> {
          try {
            return a.get(0);
          } catch (FormulaException failure) {
            if (!List.of("TYPE", "DIV_ZERO", "NUMBER_RANGE", "DATE", "NOT_FOUND", "FORMAT")
                .contains(failure.code())) {
              throw failure;
            }
            return a.get(1);
          }
        });
    add(
        functions,
        "AND",
        "LOGIC",
        1,
        256,
        "BOOLEAN",
        a -> flatten(a).stream().allMatch(FormulaValues::bool));
    add(
        functions,
        "OR",
        "LOGIC",
        1,
        256,
        "BOOLEAN",
        a -> flatten(a).stream().anyMatch(FormulaValues::bool));
    add(functions, "NOT", "LOGIC", 1, 1, "BOOLEAN", a -> !bool(a.get(0)));
    add(
        functions,
        "ISBLANK",
        "LOGIC",
        1,
        1,
        "BOOLEAN",
        a -> a.get(0) == null || "".equals(a.get(0)));
    add(functions, "ISNUMBER", "LOGIC", 1, 1, "BOOLEAN", a -> a.get(0) instanceof Number);
    add(functions, "ISTEXT", "LOGIC", 1, 1, "BOOLEAN", a -> a.get(0) instanceof String);
    add(
        functions,
        "COALESCE",
        "LOGIC",
        1,
        256,
        "ANY",
        a -> {
          for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != null && !"".equals(a.get(i))) {
              return a.get(i);
            }
          }
          return null;
        });
    for (String name : List.of("SUM", "AVERAGE", "MIN", "MAX", "COUNT", "COUNTA", "PRODUCT")) {
      add(functions, name, "AGGREGATE", 1, 256, "NUMBER", a -> aggregate(name, flatten(a)));
    }
    add(functions, "ABS", "MATH", 1, 1, "NUMBER", a -> number(a.get(0)).abs(MATH));
    add(
        functions,
        "SIGN",
        "MATH",
        1,
        1,
        "NUMBER",
        a -> BigDecimal.valueOf(number(a.get(0)).signum()));
    add(
        functions,
        "INT",
        "MATH",
        1,
        1,
        "NUMBER",
        a -> number(a.get(0)).setScale(0, RoundingMode.FLOOR));
    for (var entry :
        java.util.Map.of(
                "ROUND",
                RoundingMode.HALF_UP,
                "ROUNDUP",
                RoundingMode.UP,
                "ROUNDDOWN",
                RoundingMode.DOWN,
                "TRUNC",
                RoundingMode.DOWN)
            .entrySet()) {
      add(
          functions,
          entry.getKey(),
          "MATH",
          entry.getKey().equals("TRUNC") ? 1 : 2,
          2,
          "NUMBER",
          a ->
              number(a.get(0))
                  .setScale(a.size() == 1 ? 0 : integer(a.get(1), -38, 38), entry.getValue()));
    }
    add(
        functions,
        "MOD",
        "MATH",
        2,
        2,
        "NUMBER",
        a -> {
          BigDecimal x = number(a.get(0));
          BigDecimal y = number(a.get(1));
          if (y.signum() == 0) {
            throw new FormulaException("DIV_ZERO");
          }
          return x.subtract(y.multiply(x.divide(y, 0, RoundingMode.FLOOR)), MATH);
        });
    add(functions, "POWER", "MATH", 2, 2, "NUMBER", a -> power(number(a.get(0)), number(a.get(1))));
    add(
        functions,
        "SQRT",
        "MATH",
        1,
        1,
        "NUMBER",
        a -> {
          BigDecimal x = number(a.get(0));
          if (x.signum() < 0) {
            throw new FormulaException("NUMBER_RANGE");
          }
          return x.sqrt(MATH);
        });
    add(
        functions,
        "PI",
        "MATH",
        0,
        0,
        "NUMBER",
        a -> new BigDecimal("3.1415926535897932384626433832795028842"));
    for (String name : List.of("SIN", "COS", "TAN", "EXP", "LN", "LOG10")) {
      add(
          functions,
          name,
          "MATH",
          1,
          1,
          "NUMBER",
          a -> {
            double x = number(a.get(0)).doubleValue();
            return finite(
                switch (name) {
                  case "SIN" -> StrictMath.sin(x);
                  case "COS" -> StrictMath.cos(x);
                  case "TAN" -> StrictMath.tan(x);
                  case "EXP" -> StrictMath.exp(x);
                  case "LN" -> StrictMath.log(x);
                  default -> StrictMath.log10(x);
                });
          });
    }
    add(functions, "VALUE", "CONVERT", 1, 1, "NUMBER", a -> number(a.get(0)));
    add(functions, "NUMBER", "CONVERT", 1, 1, "NUMBER", a -> number(a.get(0)));
    add(functions, "BOOLEAN", "CONVERT", 1, 1, "BOOLEAN", a -> bool(a.get(0)));
    add(functions, "STRING", "CONVERT", 1, 1, "STRING", a -> text(a.get(0)));
    add(
        functions,
        "TEXT",
        "CONVERT",
        1,
        2,
        "STRING",
        a -> a.size() == 1 ? text(a.get(0)) : format(a.get(0), text(a.get(1))));
    add(
        functions,
        "LEN",
        "TEXT",
        1,
        1,
        "NUMBER",
        a -> BigDecimal.valueOf(text(a.get(0)).codePointCount(0, text(a.get(0)).length())));
    add(functions, "LOWER", "TEXT", 1, 1, "STRING", a -> text(a.get(0)).toLowerCase(Locale.ROOT));
    add(functions, "UPPER", "TEXT", 1, 1, "STRING", a -> text(a.get(0)).toUpperCase(Locale.ROOT));
    add(
        functions,
        "TRIM",
        "TEXT",
        1,
        1,
        "STRING",
        a -> text(a.get(0)).trim().replaceAll(" +", " "));
    add(
        functions,
        "CLEAN",
        "TEXT",
        1,
        1,
        "STRING",
        a -> {
          var result = new StringBuilder();
          text(a.get(0)).codePoints().filter(c -> c >= 32).forEach(result::appendCodePoint);
          return result.toString();
        });
    add(functions, "EXACT", "TEXT", 2, 2, "BOOLEAN", a -> text(a.get(0)).equals(text(a.get(1))));
    for (String name : List.of("CONCAT", "CONCATENATE")) {
      add(functions, name, "TEXT", 1, 256, "STRING", a -> join(flatten(a), "", false));
    }
    add(
        functions,
        "TEXTJOIN",
        "TEXT",
        3,
        256,
        "STRING",
        a -> {
          List<Object> values = new ArrayList<>();
          for (int i = 2; i < a.size(); i++) {
            Object v = a.get(i);
            if (values.size() + (v instanceof List<?> list ? list.size() : 1) > 10000) {
              throw new FormulaException("VALUE_SIZE");
            }
            if (v instanceof List<?> list) {
              values.addAll(list);
            } else {
              values.add(v);
            }
          }
          return join(values, text(a.get(0)), bool(a.get(1)));
        });
    for (String name : List.of("LEFT", "RIGHT")) {
      add(
          functions,
          name,
          "TEXT",
          1,
          2,
          "STRING",
          a -> {
            String value = text(a.get(0));
            int length = value.codePointCount(0, value.length());
            int count = Math.min(length, a.size() == 1 ? 1 : integer(a.get(1), 0, 65536));
            return name.equals("LEFT")
                ? slice(value, 0, count)
                : slice(value, length - count, count);
          });
    }
    add(
        functions,
        "MID",
        "TEXT",
        3,
        3,
        "STRING",
        a -> slice(text(a.get(0)), integer(a.get(1), 1, 65536) - 1, integer(a.get(2), 0, 65536)));
    add(
        functions,
        "REPT",
        "TEXT",
        2,
        2,
        "STRING",
        a -> {
          String value = text(a.get(0));
          int count = integer(a.get(1), 0, 65536);
          if ((long) value.length() * count > 65536) {
            throw new FormulaException("VALUE_SIZE");
          }
          return value.repeat(count);
        });
    add(
        functions,
        "SUBSTITUTE",
        "TEXT",
        3,
        4,
        "STRING",
        a ->
            substitute(
                text(a.get(0)),
                text(a.get(1)),
                text(a.get(2)),
                a.size() == 4 ? integer(a.get(3), 1, 65536) : 0));
    add(
        functions,
        "REPLACE",
        "TEXT",
        4,
        4,
        "STRING",
        a -> {
          String value = text(a.get(0));
          int start = integer(a.get(1), 1, 65536) - 1;
          int count = integer(a.get(2), 0, 65536);
          return bounded(
              slice(value, 0, start) + text(a.get(3)) + slice(value, start + count, 65536));
        });
    for (String name : List.of("FIND", "SEARCH")) {
      add(
          functions,
          name,
          "TEXT",
          2,
          3,
          "NUMBER",
          a -> {
            String needle = text(a.get(0));
            String value = text(a.get(1));
            int start = a.size() == 3 ? integer(a.get(2), 1, 65536) - 1 : 0;
            int length = value.codePointCount(0, value.length());
            if (start > length) {
              throw new FormulaException("NOT_FOUND");
            }
            String tail = slice(value, start, 65536);
            // SEARCH supports case-insensitive literal matching; no regex or wildcard execution.
            int found = -1;
            if (name.equals("SEARCH")) {
              for (int i = 0;
                  i <= tail.length() - needle.length();
                  i += i < tail.length() ? Character.charCount(tail.codePointAt(i)) : 1) {
                if (tail.regionMatches(true, i, needle, 0, needle.length())) {
                  found = i;
                  break;
                }
              }
            } else {
              found = tail.indexOf(needle);
            }
            if (found < 0) {
              throw new FormulaException("NOT_FOUND");
            }
            return BigDecimal.valueOf(start + tail.codePointCount(0, found) + 1L);
          });
    }
    add(
        functions,
        "DATE",
        "DATE",
        3,
        3,
        "DATE",
        a ->
            LocalDate.of(integer(a.get(0), 1, 9999), 1, 1)
                .plusMonths(integer(a.get(1), -1200, 1200) - 1L)
                .plusDays(integer(a.get(2), -36600, 36600) - 1L));
    add(functions, "DATEVALUE", "DATE", 1, 1, "DATE", a -> date(a.get(0)));
    add(functions, "TODAY", "DATE", 0, 0, "DATE", a -> LocalDate.now(a.clock()));
    add(functions, "NOW", "DATE", 0, 0, "DATETIME", a -> LocalDateTime.now(a.clock()));
    for (String name : List.of("YEAR", "MONTH", "DAY")) {
      add(
          functions,
          name,
          "DATE",
          1,
          1,
          "NUMBER",
          a -> {
            LocalDate d = date(a.get(0));
            return BigDecimal.valueOf(
                switch (name) {
                  case "YEAR" -> d.getYear();
                  case "MONTH" -> d.getMonthValue();
                  default -> d.getDayOfMonth();
                });
          });
    }
    add(
        functions,
        "DAYS",
        "DATE",
        2,
        2,
        "NUMBER",
        a -> BigDecimal.valueOf(ChronoUnit.DAYS.between(date(a.get(1)), date(a.get(0)))));
    return List.copyOf(functions);
  }

  private static void add(
      List<FormulaFunction> functions,
      String name,
      String category,
      int min,
      int max,
      String result,
      FormulaFunction.Body body) {
    functions.add(new FormulaFunction(name, category, min, max, result, parameters(name), body));
  }

  private static List<String> parameters(String name) {
    return switch (name) {
      case "IF" -> List.of("condition", "whenTrue", "whenFalse");
      case "IFERROR" -> List.of("value", "fallback");
      case "ROUND", "ROUNDUP", "ROUNDDOWN", "TRUNC" -> List.of("number", "digits");
      case "POWER" -> List.of("base", "exponent");
      case "MOD" -> List.of("number", "divisor");
      case "LEFT", "RIGHT" -> List.of("text", "count");
      case "MID" -> List.of("text", "start", "count");
      case "FIND", "SEARCH" -> List.of("findText", "withinText", "start");
      case "SUBSTITUTE" -> List.of("text", "oldText", "newText", "occurrence");
      case "REPLACE" -> List.of("text", "start", "count", "newText");
      case "REPT" -> List.of("text", "count");
      case "EXACT" -> List.of("firstText", "secondText");
      case "TEXT" -> List.of("value", "format");
      case "TEXTJOIN" -> List.of("delimiter", "ignoreEmpty", "values");
      case "DATE" -> List.of("year", "month", "day");
      case "DAYS" -> List.of("endDate", "startDate");
      case "PI", "TODAY", "NOW" -> List.of();
      default -> List.of("value");
    };
  }

  static BigDecimal power(BigDecimal base, BigDecimal exponent) {
    if (base.signum() == 0 && exponent.signum() < 0) {
      throw new FormulaException("DIV_ZERO");
    }
    if (exponent.stripTrailingZeros().scale() > 0) {
      return finite(StrictMath.pow(base.doubleValue(), exponent.doubleValue()));
    }
    int count = integer(exponent, -1000, 1000);
    return count >= 0 ? base.pow(count, MATH) : BigDecimal.ONE.divide(base.pow(-count, MATH), MATH);
  }

  private static BigDecimal finite(double value) {
    if (!Double.isFinite(value)) {
      throw new FormulaException("NUMBER_RANGE");
    }
    return BigDecimal.valueOf(value);
  }

  private static Object aggregate(String name, List<Object> values) {
    List<Object> present = values.stream().filter(v -> v != null && !"".equals(v)).toList();
    if (name.equals("COUNTA")) {
      return BigDecimal.valueOf(present.size());
    }
    var numbers = new ArrayList<BigDecimal>();
    for (Object value : present) {
      try {
        numbers.add(number(value));
      } catch (FormulaException failure) {
        if (!name.equals("COUNT") || !failure.code().equals("TYPE")) {
          throw failure;
        }
      }
    }
    if (name.equals("COUNT")) {
      return BigDecimal.valueOf(numbers.size());
    }
    if (numbers.isEmpty()) {
      if (name.equals("AVERAGE")) {
        throw new FormulaException("DIV_ZERO");
      }
      return BigDecimal.ZERO;
    }
    return switch (name) {
      case "MIN" -> numbers.stream().min(BigDecimal::compareTo).orElseThrow();
      case "MAX" -> numbers.stream().max(BigDecimal::compareTo).orElseThrow();
      case "PRODUCT" ->
          numbers.stream().reduce(BigDecimal.ONE, (a, b) -> number(a.multiply(b, MATH)));
      case "AVERAGE" ->
          numbers.stream()
              .reduce(BigDecimal.ZERO, (a, b) -> a.add(b, MATH))
              .divide(BigDecimal.valueOf(numbers.size()), MATH);
      default -> numbers.stream().reduce(BigDecimal.ZERO, (a, b) -> a.add(b, MATH));
    };
  }

  private static String join(List<Object> values, String delimiter, boolean ignoreEmpty) {
    var result = new StringBuilder();
    boolean first = true;
    for (Object value : values) {
      String item = text(value);
      if (ignoreEmpty && item.isEmpty()) {
        continue;
      }
      if ((long) result.length() + item.length() + delimiter.length() > 65536) {
        throw new FormulaException("VALUE_SIZE");
      }
      if (!first) {
        result.append(delimiter);
      }
      result.append(item);
      first = false;
    }
    return result.toString();
  }

  private static String slice(String value, int start, int count) {
    int size = value.codePointCount(0, value.length());
    int from = Math.min(start, size);
    int to = Math.min(from + count, size);
    return value.substring(value.offsetByCodePoints(0, from), value.offsetByCodePoints(0, to));
  }

  private static String substitute(
      String value, String search, String replacement, int occurrence) {
    if (search.isEmpty()) {
      return value;
    }
    var result = new StringBuilder();
    int from = 0;
    int count = 0;
    int found;
    while ((found = value.indexOf(search, from)) >= 0) {
      String part = ++count == occurrence || occurrence == 0 ? replacement : search;
      if ((long) result.length() + found - from + part.length() > 65536) {
        throw new FormulaException("VALUE_SIZE");
      }
      result.append(value, from, found).append(part);
      from = found + search.length();
    }
    return bounded(result.append(value, from, value.length()).toString());
  }

  private static LocalDate date(Object value) {
    if (value instanceof LocalDate date) {
      return date;
    }
    if (value instanceof LocalDateTime date) {
      return date.toLocalDate();
    }
    try {
      return LocalDate.parse(text(value));
    } catch (java.time.DateTimeException failure) {
      throw new FormulaException("DATE");
    }
  }

  private static String format(Object value, String pattern) {
    if (pattern.matches("0(\\.0{1,38})?%?")) {
      boolean percent = pattern.endsWith("%");
      int dot = pattern.indexOf('.');
      int scale = dot < 0 ? 0 : pattern.length() - dot - 1 - (percent ? 1 : 0);
      return number(value)
              .multiply(percent ? BigDecimal.valueOf(100) : BigDecimal.ONE)
              .setScale(scale, RoundingMode.HALF_UP)
              .toPlainString()
          + (percent ? "%" : "");
    }
    return switch (pattern.toLowerCase(Locale.ROOT)) {
      case "yyyy-mm-dd" -> date(value).toString();
      case "yyyy/mm/dd" -> date(value).format(DateTimeFormatter.ofPattern("uuuu/MM/dd"));
      case "yyyy", "mm", "dd" ->
          date(value)
              .format(
                  DateTimeFormatter.ofPattern(
                      switch (pattern.toLowerCase(Locale.ROOT)) {
                        case "yyyy" -> "uuuu";
                        case "mm" -> "MM";
                        default -> "dd";
                      }));
      default -> throw new FormulaException("FORMAT");
    };
  }
}
