package org.simplepoint.calculation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FormulaEngineTest {
  private final FormulaEngine engine =
      new FormulaEngine(
          StandardFunctions.all(),
          Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC));

  private Object value(String expression) {
    return engine.compile(expression).evaluate(Map.of());
  }

  private void decimal(String expected, String expression) {
    assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) value(expression)), expression);
  }

  private void error(String code, Runnable action) {
    assertEquals(code, assertThrows(FormulaException.class, action::run).code());
  }

  @Test
  void decimalArithmeticAndExcelPrecedence() {
    decimal("0.3", "=0.1+0.2");
    decimal("9", "=-3^2");
    decimal("64", "=2^3^2");
    decimal("0.0009", "=3%^2");
    decimal("10", "=2+4*2");
    decimal("-1.24", "=ROUND(-1.235,2)");
    decimal("-1", "=MOD(3,-2)");
    assertEquals(true, value("=1+2=3"));
    assertEquals("23", value("=1+1&3"));
  }

  @Test
  void stringsCountCodePointsAndEscapeWithoutExecutingAnything() {
    decimal("4", "=LEN(\"汉字😀日\")");
    assertEquals("😀", value("=MID(\"汉😀日\",2,1)"));
    assertEquals("\"hello\"", value("=\"\"\"hello\"\"\""));
    decimal("3", "=SEARCH(\"x\",\"İ😀X\")");
    assertEquals("<script>", value("=\"<script>\""));
    var tree = FormulaSyntax.parse("=[费用]]代码]+[日本語]");
    assertEquals(Set.of("费用]代码", "日本語"), FormulaSyntax.references(tree));
    assertEquals(tree, FormulaSyntax.parse(FormulaSyntax.print(tree)));
    assertEquals(Set.of("a", "b"), FormulaSyntax.references(FormulaSyntax.parse("=[a]+[b]")));
  }

  @Test
  void branchesAreLazyButAllFunctionsAreValidated() {
    decimal("7", "=IF(FALSE,1/0,7)");
    decimal("8", "=IFERROR(1/0,8)");
    assertEquals("ok", value("=IFERROR(DATEVALUE(\"invalid\"),\"ok\")"));
    error("UNKNOWN_FUNCTION", () -> value("=IF(FALSE,EXEC(1),7)"));
    error("ARGUMENT_COUNT", () -> value("=ROUND(1)"));
    error("DIV_ZERO", () -> value("=1/0"));
  }

  @Test
  void fieldListsAggregateAndNullRemainDistinctFromMissing() {
    var formula = engine.compile("=SUM([detail.amount])+[adjustment]");
    var inputs = new java.util.LinkedHashMap<String, Object>();
    inputs.put(
        "detail.amount", Arrays.asList(new BigDecimal("1.25"), null, new BigDecimal("2.75")));
    inputs.put("adjustment", null);
    assertEquals(0, new BigDecimal("4").compareTo((BigDecimal) formula.evaluate(inputs)));
    inputs.remove("adjustment");
    error("UNKNOWN_FIELD", () -> formula.evaluate(inputs));
    error("TYPE", () -> engine.compile("=[x]").evaluate(Map.of("x", List.of(List.of(1)))));
  }

  @Test
  void clockAndExtensionsAreSuppliedByIndependentHost() {
    assertEquals(LocalDate.of(2026, 10, 16), value("=TODAY()"));
    var functions = new ArrayList<>(StandardFunctions.all());
    functions.add(
        new FormulaFunction(
            "DOUBLE",
            "CUSTOM",
            1,
            1,
            "NUMBER",
            List.of("value"),
            args -> FormulaValues.number(args.get(0)).multiply(BigDecimal.TWO)));
    var custom = new FormulaEngine(functions, Clock.systemUTC());
    assertEquals(new BigDecimal("6"), custom.compile("=DOUBLE(3)").evaluate(Map.of()));
    functions.add(functions.getFirst());
    error("DUPLICATE_FUNCTION", () -> new FormulaEngine(functions, Clock.systemUTC()));
  }

  @Test
  void boundedGrammarAstInputsAndOutputs() {
    error("EXPRESSION_DEPTH", () -> value("(".repeat(65) + "1" + ")".repeat(65)));
    error("NUMBER_RANGE", () -> value("=1e999999"));
    error("VALUE_SIZE", () -> value("=IFERROR(REPT(\"xx\",65536),0)"));
    error("SYNTAX", () -> value("=Runtime.exec(\"cmd\");"));
    error("INVALID_AST", () -> engine.compile(FormulaNode.leaf(FormulaNode.Kind.NUMBER, "1+2")));
    error("VALUE_SIZE", () -> engine.compile("=[x]").evaluate(Map.of("x", "x".repeat(65537))));
    error("TYPE", () -> engine.compile("=[x]").evaluate(Map.of("x", Double.NaN)));
  }

  @Test
  void formulaPlansSortDependenciesAndRejectCyclesOrForgedResults() {
    var plan =
        new FormulaPlan(
            engine,
            Map.of("total", "[net]+[tax]", "net", "[price]*[quantity]"),
            Set.of("price", "quantity", "tax"));
    assertEquals(List.of("net", "total"), plan.order());
    assertEquals(Set.of("net", "price", "quantity", "tax"), plan.dependencies().get("total"));
    assertEquals(
        new BigDecimal("26"),
        plan.evaluate(Map.of("price", 6, "quantity", 4, "tax", 2)).get("total"));
    error("COMPUTED_WRITE", () -> plan.evaluate(Map.of("net", 4)));
    error(
        "CIRCULAR_REFERENCE",
        () -> new FormulaPlan(engine, Map.of("a", "[b]", "b", "[a]"), Set.of()));
    error("UNKNOWN_FIELD", () -> new FormulaPlan(engine, Map.of("a", "[missing]"), Set.of()));
  }
}
