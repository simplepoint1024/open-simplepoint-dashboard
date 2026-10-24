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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Explicit null, type/coercion and boundary contracts for every independently usable built-in. */
class StandardFunctionBoundaryTest {
  private static List<String[]> rows() throws IOException {
    try (var reader = new BufferedReader(new InputStreamReader(
        Objects.requireNonNull(StandardFunctionBoundaryTest.class.getResourceAsStream("/function-boundaries.tsv")),
        StandardCharsets.UTF_8))) {
      return reader.lines().filter(line -> !line.isBlank() && !line.startsWith("#"))
          .map(line -> line.split("\t", -1)).toList();
    }
  }

  static Stream<Arguments> cases() throws IOException {
    return rows().stream().map(row -> Arguments.of(row[0], row[1], row[2], row[3], row[4]));
  }

  @Test
  void everyRegisteredFunctionHasExplicitNullTypeAndBoundaryContracts() throws IOException {
    var expected = StandardFunctions.all().stream().flatMap(function ->
        Stream.of("null", "type", "boundary").map(kind -> function.name() + ":" + kind))
        .collect(Collectors.toSet());
    Set<String> actual = new HashSet<>();
    for (var row : rows()) {
      assertEquals(5, row.length);
      assertTrue(actual.add(row[0] + ":" + row[1]), "Duplicate edge contract");
      assertTrue(row[2].startsWith(row[0] + "("));
    }
    assertEquals(expected, actual);
  }

  @ParameterizedTest(name = "{0}: {1} — {2}")
  @MethodSource("cases")
  void evaluatesDocumentedEdges(String function, String kind, String expression, String type, String expected) {
    var engine = new FormulaEngine(StandardFunctions.all(),
        Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC));
    var tree = FormulaSyntax.parse(expression);
    assertEquals(tree, FormulaSyntax.parse(FormulaSyntax.print(tree)), function + " " + kind);
    Map<String, Object> inputs = Map.of("list", List.of(BigDecimal.ONE, BigDecimal.TWO), "empty", List.of());
    if (type.equals("ERROR")) {
      var failure = assertThrows(FormulaException.class, () -> engine.compile(tree).evaluate(inputs));
      assertEquals(expected, failure.code());
      return;
    }
    Object actual = engine.compile(tree).evaluate(inputs);
    switch (type) {
      case "NUMBER" -> assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) actual));
      case "BOOLEAN" -> assertEquals(Boolean.valueOf(expected), actual);
      case "DATE" -> assertEquals(LocalDate.parse(expected), actual);
      case "DATETIME" -> assertEquals(LocalDateTime.parse(expected), actual);
      case "NULL" -> assertEquals(null, actual);
      case "LIST" -> assertEquals(List.of(BigDecimal.ONE, BigDecimal.TWO), actual);
      case "STRING" -> assertEquals(expected, actual);
      default -> throw new IllegalArgumentException("Unknown edge result type: " + type);
    }
  }
}
