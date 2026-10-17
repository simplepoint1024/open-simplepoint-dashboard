package org.simplepoint.calculation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validated dependency graph for a record. Field identifiers are opaque application-owned keys. */
public final class FormulaPlan {
  private final FormulaEngine engine;
  private final Map<String, FormulaEngine.CompiledFormula> formulas;
  private final List<String> order;
  private final Map<String, Set<String>> dependencies;

  /** Compiles a bounded acyclic graph with explicit input field identities. */
  public FormulaPlan(
      FormulaEngine engine, Map<String, String> expressions, Set<String> inputFields) {
    this.engine = engine;
    if (expressions.size() > 256 || inputFields.size() > 4096) {
      throw new FormulaException("PLAN_SIZE");
    }
    var compiled = new LinkedHashMap<String, FormulaEngine.CompiledFormula>();
    expressions.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry -> {
              if (inputFields.contains(entry.getKey())) {
                throw new FormulaException("DUPLICATE_FIELD");
              }
              compiled.put(entry.getKey(), engine.compile(entry.getValue()));
            });
    formulas = Collections.unmodifiableMap(compiled);
    var sorted = new ArrayList<String>();
    var active = new LinkedHashSet<String>();
    var visited = new LinkedHashSet<String>();
    for (String field : compiled.keySet()) {
      visit(field, inputFields, active, visited, sorted);
    }
    order = List.copyOf(sorted);
    var sources = new LinkedHashMap<String, Set<String>>();
    for (String field : order) {
      var transitive = new LinkedHashSet<String>();
      for (String reference : compiled.get(field).references()) {
        transitive.add(reference);
        transitive.addAll(sources.getOrDefault(reference, Set.of()));
      }
      sources.put(field, Collections.unmodifiableSet(transitive));
    }
    dependencies = Collections.unmodifiableMap(sources);
  }

  private void visit(
      String field,
      Set<String> inputs,
      Set<String> active,
      Set<String> visited,
      List<String> sorted) {
    if (visited.contains(field)) {
      return;
    }
    if (!active.add(field)) {
      throw new FormulaException("CIRCULAR_REFERENCE");
    }
    for (String reference : formulas.get(field).references()) {
      if (formulas.containsKey(reference)) {
        visit(reference, inputs, active, visited, sorted);
      } else if (!inputs.contains(reference)) {
        throw new FormulaException("UNKNOWN_FIELD");
      }
    }
    active.remove(field);
    visited.add(field);
    sorted.add(field);
  }

  /** Returns the stable topological evaluation order. */
  public List<String> order() {
    return order;
  }

  /** Includes intermediate calculated fields for application-level permission intersection. */
  public Map<String, Set<String>> dependencies() {
    return dependencies;
  }

  /** Evaluates from original inputs and rejects forged calculated values. */
  public Map<String, Object> evaluate(Map<String, ?> inputs) {
    var values = new LinkedHashMap<String, Object>();
    values.putAll(inputs);
    if (formulas.keySet().stream().anyMatch(inputs::containsKey)) {
      throw new FormulaException("COMPUTED_WRITE");
    }
    final var clock = engine.snapshotClock();
    var results = new LinkedHashMap<String, Object>();
    for (String field : order) {
      Object value = formulas.get(field).evaluate(reference -> {
        if (!values.containsKey(reference)) {
          throw new FormulaException("UNKNOWN_FIELD");
        }
        return values.get(reference);
      }, clock);
      values.put(field, value);
      results.put(field, value);
    }
    return Collections.unmodifiableMap(results);
  }
}
