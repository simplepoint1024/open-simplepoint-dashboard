package org.simplepoint.calculation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class StandardFunctionsTest {
  static Stream<Arguments> examples() {
    return Stream.of(
        example("IF(TRUE,\"yes\",1/0)", "yes"),
        example("IFERROR(TEXT(1,\"unknown\"),\"fallback\")", "fallback"),
        example("AND(TRUE,1,0)", false),
        example("OR(FALSE,0,1)", true),
        example("NOT(0)", true),
        example("ISBLANK(NULL)", true),
        example("ISNUMBER(\"1\")", false),
        example("ISTEXT(\"1\")", true),
        example("COALESCE(NULL,\"\",\"available\")", "available"),
        example("SUM(1,2,3)", new BigDecimal("6")),
        example("AVERAGE(1,2,3)", new BigDecimal("2")),
        example("MIN(1,-2,3)", new BigDecimal("-2")),
        example("MAX(1,-2,3)", new BigDecimal("3")),
        example("COUNT(1,\"two\",\"3\",NULL)", new BigDecimal("2")),
        example("COUNTA(0,FALSE,\"\",NULL)", new BigDecimal("2")),
        example("PRODUCT(1.2,3)", new BigDecimal("3.6")),
        example("ABS(-1.25)", new BigDecimal("1.25")),
        example("SIGN(-0.1)", new BigDecimal("-1")),
        example("INT(-1.1)", new BigDecimal("-2")),
        example("ROUND(1.235,2)", new BigDecimal("1.24")),
        example("ROUNDUP(-1.231,2)", new BigDecimal("-1.24")),
        example("ROUNDDOWN(-1.239,2)", new BigDecimal("-1.23")),
        example("TRUNC(-1.9)", new BigDecimal("-1")),
        example("MOD(-3,2)", new BigDecimal("1")),
        example("POWER(2,-3)", new BigDecimal("0.125")),
        example("SQRT(9)", new BigDecimal("3")),
        example("PI()", new BigDecimal("3.1415926535897932384626433832795028842")),
        example("SIN(0)", BigDecimal.ZERO),
        example("COS(0)", BigDecimal.ONE),
        example("TAN(0)", BigDecimal.ZERO),
        example("EXP(0)", BigDecimal.ONE),
        example("LN(1)", BigDecimal.ZERO),
        example("LOG10(100)", new BigDecimal("2")),
        example("VALUE(\"1.25\")", new BigDecimal("1.25")),
        example("NUMBER(TRUE)", BigDecimal.ONE),
        example("BOOLEAN(0)", false),
        example("STRING(TRUE)", "TRUE"),
        example("TEXT(0.125,\"0.0%\")", "12.5%"),
        example("LEN(\"汉😀日\")", new BigDecimal("3")),
        example("LOWER(\"ABC汉\")", "abc汉"),
        example("UPPER(\"abc汉\")", "ABC汉"),
        example("TRIM(\"  a   b  \")", "a b"),
        example("CLEAN(\"a\tb\")", "ab"),
        example("EXACT(\"a\",\"A\")", false),
        example("CONCAT(\"a\",1,TRUE)", "a1TRUE"),
        example("CONCATENATE(\"a\",\"b\")", "ab"),
        example("TEXTJOIN(\",\",TRUE,\"a\",\"\",NULL,\"b\")", "a,b"),
        example("LEFT(\"😀汉日\",2)", "😀汉"),
        example("RIGHT(\"汉日😀\")", "😀"),
        example("MID(\"汉😀日\",2,1)", "😀"),
        example("REPT(\"汉\",3)", "汉汉汉"),
        example("SUBSTITUTE(\"a-b-b\",\"b\",\"x\",2)", "a-b-x"),
        example("REPLACE(\"汉😀日\",2,1,\"字\")", "汉字日"),
        example("FIND(\"汉\",\"汉字汉\",2)", new BigDecimal("3")),
        example("SEARCH(\"x\",\"İ😀X\")", new BigDecimal("3")),
        example("DATE(2024,2,30)", LocalDate.of(2024, 3, 1)),
        example("DATEVALUE(\"2024-02-29\")", LocalDate.of(2024, 2, 29)),
        example("TODAY()", LocalDate.of(2026, 10, 16)),
        example("NOW()", LocalDateTime.of(2026, 10, 16, 0, 0)),
        example("YEAR(\"2024-02-29\")", new BigDecimal("2024")),
        example("MONTH(\"2024-02-29\")", new BigDecimal("2")),
        example("DAY(\"2024-02-29\")", new BigDecimal("29")),
        example("DAYS(\"2024-03-01\",\"2024-02-28\")", new BigDecimal("2")));
  }

  private static Arguments example(String expression, Object expected) {
    return Arguments.of(expression, expected);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("examples")
  void evaluatesDocumentedFunctionExamples(String expression, Object expected) {
    var engine =
        new FormulaEngine(
            StandardFunctions.all(),
            Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC));
    Object actual = engine.compile(expression).evaluate(Map.of());
    if (expected instanceof BigDecimal decimal) {
      assertEquals(0, decimal.compareTo((BigDecimal) actual));
    } else {
      assertEquals(expected, actual);
    }
  }
}
