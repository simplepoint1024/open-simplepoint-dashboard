package org.simplepoint.calculation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FormulaAggregatePlanTest {
  @Test
  void computesDetailAmountsThenParentTotalsWithoutChangingInputs() {
    var plan =
        new FormulaAggregatePlan(
            new FormulaEngine(),
            new FormulaAggregatePlan.Scope(Set.of("rate"), Map.of("total", "SUM([items.amount])")),
            Map.of(
                "items",
                new FormulaAggregatePlan.Scope(
                    Set.of("price", "quantity"),
                    Map.of("amount", "[price]*[quantity]*[parent.rate]"))));
    var rows = List.of(Map.of("price", 10, "quantity", 2), Map.of("price", 15, "quantity", 3));
    var result = plan.evaluate(Map.of("rate", new BigDecimal("0.8")), Map.of("items", rows));
    assertEquals(new BigDecimal("52.0"), result.parent().get("total"));
    assertEquals(new BigDecimal("16.0"), result.details().get("items").getFirst().get("amount"));
    assertEquals(Set.of("price", "quantity"), rows.getFirst().keySet());
    assertEquals(
        Set.of("items.amount", "items.price", "items.quantity", "parent.rate"),
        plan.dependencies().get("parent.total"));
    assertEquals(
        BigDecimal.ZERO,
        plan.evaluate(Map.of("rate", 1), Map.of("items", List.of())).parent().get("total"));
  }

  @Test
  void catchesCyclesAcrossParentAndDetailEvenBeforeRowsExist() {
    var failure =
        assertThrows(
            FormulaException.class,
            () ->
                new FormulaAggregatePlan(
                    new FormulaEngine(),
                    new FormulaAggregatePlan.Scope(
                        Set.of(), Map.of("total", "SUM([items.amount])")),
                    Map.of(
                        "items",
                        new FormulaAggregatePlan.Scope(
                            Set.of(), Map.of("amount", "[parent.total]*2")))));
    assertEquals("CIRCULAR_REFERENCE", failure.code());
  }

  @Test
  void rejectsForeignDetailReferencesAndForgedComputedValues() {
    assertEquals(
        "UNKNOWN_FIELD",
        assertThrows(
                FormulaException.class,
                () ->
                    new FormulaAggregatePlan(
                        new FormulaEngine(),
                        new FormulaAggregatePlan.Scope(Set.of(), Map.of()),
                        Map.of(
                            "items",
                            new FormulaAggregatePlan.Scope(
                                Set.of(), Map.of("amount", "[other.amount]")))))
            .code());
    var plan =
        new FormulaAggregatePlan(
            new FormulaEngine(),
            new FormulaAggregatePlan.Scope(Set.of(), Map.of("total", "1")),
            Map.of());
    assertEquals(
        "COMPUTED_WRITE",
        assertThrows(FormulaException.class, () -> plan.evaluate(Map.of("total", 999), Map.of()))
            .code());
  }

  @Test
  void sharesOneClockSnapshotAndBoundsAggregateOutput() {
    var ticks = new java.util.concurrent.atomic.AtomicInteger();
    var clock =
        new java.time.Clock() {
          @Override
          public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
          }

          @Override
          public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public java.time.Instant instant() {
            return java.time.Instant.EPOCH.plusSeconds(ticks.getAndIncrement());
          }
        };
    var engine = new FormulaEngine(StandardFunctions.all(), clock);
    var plan =
        new FormulaAggregatePlan(
            engine,
            new FormulaAggregatePlan.Scope(Set.of(), Map.of("at", "NOW()")),
            Map.of("rows", new FormulaAggregatePlan.Scope(Set.of(), Map.of("at", "NOW()"))));
    var result = plan.evaluate(Map.of(), Map.of("rows", List.of(Map.of(), Map.of())));
    assertEquals(result.parent().get("at"), result.details().get("rows").getFirst().get("at"));
    assertEquals(1, ticks.get());
    var bounded =
        new FormulaAggregatePlan(
            engine,
            new FormulaAggregatePlan.Scope(Set.of(), Map.of()),
            Map.of(
                "rows",
                new FormulaAggregatePlan.Scope(Set.of(), Map.of("large", "REPT(\"x\",65536)"))));
    assertEquals(
        "EVALUATION_LIMIT",
        assertThrows(
                FormulaException.class,
                () ->
                    bounded.evaluate(
                        Map.of(), Map.of("rows", java.util.Collections.nCopies(17, Map.of()))))
            .code());
  }
}
