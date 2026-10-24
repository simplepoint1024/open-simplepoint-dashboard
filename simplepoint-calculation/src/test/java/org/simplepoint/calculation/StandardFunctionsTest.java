package org.simplepoint.calculation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class StandardFunctionsTest {
  static Stream<Arguments> examples() throws IOException {
    return acceptanceRows().stream().map(parts -> Arguments.of(parts[1], expected(parts[2], parts[3])));
  }

  private static List<String[]> acceptanceRows() throws IOException {
    try (var reader = new BufferedReader(new InputStreamReader(
        Objects.requireNonNull(StandardFunctionsTest.class.getResourceAsStream("/function-acceptance.tsv")),
        StandardCharsets.UTF_8))) {
      return reader.lines().filter(line -> !line.isBlank() && !line.startsWith("#"))
          .map(line -> Arrays.stream(line.split("\\t", -1)).map(String::translateEscapes).toArray(String[]::new))
          .toList();
    }
  }

  private static Object expected(String type, String value) {
    return switch (type) {
      case "NUMBER" -> new BigDecimal(value);
      case "BOOLEAN" -> Boolean.valueOf(value);
      case "DATE" -> LocalDate.parse(value);
      case "DATETIME" -> LocalDateTime.parse(value);
      case "STRING" -> value;
      default -> throw new IllegalArgumentException("Unknown acceptance value type");
    };
  }

  @Test
  void everyRegisteredFunctionHasExactlyOneReusableAcceptanceExample() throws Exception {
    var rows = acceptanceRows();
    var names = new HashSet<String>();
    for (var row : rows) {
      assertEquals(4, row.length);
      assertTrue(names.add(row[0]), "Duplicate function example: " + row[0]);
      assertTrue(row[1].startsWith(row[0] + "("));
    }
    assertEquals(StandardFunctions.all().stream().map(FormulaFunction::name).collect(Collectors.toSet()), names);
  }

  @Test
  void everyRegisteredFunctionRejectsCallsOutsideItsArityContract() {
    var engine = new FormulaEngine();
    for (var function : StandardFunctions.all()) {
      if (function.minArguments() > 0) {
        var missing = assertThrows(FormulaException.class,
            () -> engine.compile(function.name() + "(" + String.join(",", Collections.nCopies(function.minArguments() - 1, "1")) + ")"));
        assertEquals("ARGUMENT_COUNT", missing.code(), function.name());
      }
      var excess = assertThrows(FormulaException.class,
          () -> engine.compile(function.name() + "(" + String.join(",", Collections.nCopies(function.maxArguments() + 1, "1")) + ")"));
      assertEquals(function.maxArguments() == 256 ? "INVALID_AST" : "ARGUMENT_COUNT", excess.code(), function.name());
    }
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
