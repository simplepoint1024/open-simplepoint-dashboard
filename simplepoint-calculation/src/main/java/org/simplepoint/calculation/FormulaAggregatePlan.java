package org.simplepoint.calculation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Independent parent/detail dependency graph. Parent formulas use [detail.field]; detail formulas
 * use local [field] or [parent.field]. Hosts supply authorized, version-consistent input snapshots.
 */
public final class FormulaAggregatePlan {
  /** Immutable declarations for one record scope, without storage or platform metadata. */
  public record Scope(Set<String> inputs, Map<String, String> formulas) {
    /** Copies declarations and prevents ambiguous cross-scope identifiers. */
    public Scope {
      inputs = Set.copyOf(inputs);
      formulas = Map.copyOf(formulas);
      var identities = new LinkedHashSet<>(inputs);
      identities.addAll(formulas.keySet());
      if (identities.size() > 4096 || formulas.size() > 256) {
        throw new FormulaException("PLAN_SIZE");
      }
      for (String identity : identities) {
        identifier(identity);
      }
      if (inputs.stream().anyMatch(formulas::containsKey)) {
        throw new FormulaException("DUPLICATE_FIELD");
      }
    }
  }

  /** Computed outputs only, with detail rows in the same order as the input snapshot. */
  public record Result(Map<String, Object> parent, Map<String, List<Map<String, Object>>> details) {
    /** Makes all output maps immutable while preserving null calculation results. */
    public Result {
      parent = immutable(parent);
      var collections = new LinkedHashMap<String, List<Map<String, Object>>>();
      details.forEach(
          (key, rows) ->
              collections.put(key, rows.stream().map(FormulaAggregatePlan::immutable).toList()));
      details = Collections.unmodifiableMap(collections);
    }
  }

  private record Field(String scope, String id) {
    String key() {
      return scope + "." + id;
    }
  }

  private final FormulaEngine engine;
  private final Scope parent;
  private final Map<String, Scope> details;
  private final Map<String, Field> identities;
  private final Map<String, FormulaEngine.CompiledFormula> compiled;
  private final FormulaPlan graph;

  /** Compiles and validates local, parent and aggregate dependencies before any evaluation. */
  public FormulaAggregatePlan(FormulaEngine engine, Scope parent, Map<String, Scope> details) {
    if (details.size() > 32) {
      throw new FormulaException("PLAN_SIZE");
    }
    this.engine = engine;
    this.parent = parent;
    this.details = Map.copyOf(details);
    var scopes = new LinkedHashMap<String, Scope>();
    scopes.put("parent", parent);
    details.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry -> {
              identifier(entry.getKey());
              if (entry.getKey().equals("parent")) {
                throw new FormulaException("DUPLICATE_FIELD");
              }
              scopes.put(entry.getKey(), entry.getValue());
            });
    var fields = new LinkedHashMap<String, Field>();
    var expressions = new LinkedHashMap<String, String>();
    var inputs = new LinkedHashSet<String>();
    var formulas = new LinkedHashMap<String, FormulaEngine.CompiledFormula>();
    scopes.forEach(
        (scope, declaration) -> {
          declaration.inputs().forEach(id -> inputs.add(new Field(scope, id).key()));
          declaration
              .formulas()
              .forEach(
                  (id, expression) -> {
                    var field = new Field(scope, id);
                    var formula = engine.compile(expression);
                    fields.put(field.key(), field);
                    formulas.put(field.key(), formula);
                    expressions.put(
                        field.key(), FormulaSyntax.print(qualify(formula.tree(), scope)));
                  });
        });
    graph = new FormulaPlan(engine, expressions, inputs);
    identities = Map.copyOf(fields);
    compiled = Map.copyOf(formulas);
  }

  /** Transitive qualified dependencies let hosts intersect field permissions across scopes. */
  public Map<String, Set<String>> dependencies() {
    return graph.dependencies();
  }

  /**
   * Evaluates one bounded aggregate from original inputs; clients cannot supply computed values.
   */
  public Result evaluate(
      Map<String, ?> parentInputs,
      Map<String, ? extends List<? extends Map<String, ?>>> detailInputs) {
    if (!detailInputs.keySet().equals(details.keySet())) {
      throw new FormulaException("UNKNOWN_COLLECTION");
    }
    final Map<String, Object> parentValues = snapshot(parent, parentInputs);
    var detailValues = new LinkedHashMap<String, List<Map<String, Object>>>();
    int rowCount = 0;
    for (var entry : details.entrySet()) {
      var rows = detailInputs.get(entry.getKey());
      rowCount += rows.size();
      if (rowCount > 10000) {
        throw new FormulaException("VALUE_SIZE");
      }
      detailValues.put(
          entry.getKey(), rows.stream().map(row -> snapshot(entry.getValue(), row)).toList());
    }
    long work = 0;
    for (String key : graph.order()) {
      Field field = identities.get(key);
      int rows = field.scope().equals("parent") ? 1 : detailValues.get(field.scope()).size();
      work += (long) nodes(compiled.get(key).tree()) * rows;
      if (work > 100000) {
        throw new FormulaException("EVALUATION_LIMIT");
      }
    }
    var parentResults = new LinkedHashMap<String, Object>();
    var detailResults = new LinkedHashMap<String, List<Map<String, Object>>>();
    detailValues.forEach(
        (key, rows) -> {
          var results = new ArrayList<Map<String, Object>>(rows.size());
          rows.forEach(ignored -> results.add(new LinkedHashMap<>()));
          detailResults.put(key, results);
        });
    final var clock = engine.snapshotClock();
    final var budget = new ResultBudget();
    for (String key : graph.order()) {
      Field field = identities.get(key);
      var formula = compiled.get(key);
      if (field.scope().equals("parent")) {
        Object result =
            formula.evaluate(
                reference -> {
                  Field source = reference(reference, "parent");
                  if (source.scope().equals("parent")) {
                    return required(parentValues, source.id());
                  }
                  return detailValues.get(source.scope()).stream()
                      .map(row -> required(row, source.id()))
                      .toList();
                }, clock);
        budget.accept(result);
        parentValues.put(field.id(), result);
        parentResults.put(field.id(), result);
      } else {
        var rows = detailValues.get(field.scope());
        for (int index = 0; index < rows.size(); index++) {
          Map<String, Object> row = rows.get(index);
          Object result =
              formula.evaluate(
                  reference -> {
                    Field source = reference(reference, field.scope());
                    return required(
                        source.scope().equals("parent") ? parentValues : row, source.id());
                  }, clock);
          budget.accept(result);
          row.put(field.id(), result);
          detailResults.get(field.scope()).get(index).put(field.id(), result);
        }
      }
    }
    return new Result(parentResults, detailResults);
  }

  private static final class ResultBudget {
    private int items;
    private long characters;

    void accept(Object value) {
      if (++items > 100000) {
        throw new FormulaException("EVALUATION_LIMIT");
      }
      if (value instanceof String text) {
        characters += text.length();
        if (characters > 1048576) {
          throw new FormulaException("EVALUATION_LIMIT");
        }
      } else if (value instanceof List<?> list) {
        list.forEach(this::accept);
      }
    }
  }

  private static FormulaNode qualify(FormulaNode node, String scope) {
    if (node.kind() == FormulaNode.Kind.FIELD) {
      return FormulaNode.leaf(FormulaNode.Kind.FIELD, reference(node.text(), scope).key());
    }
    return new FormulaNode(
        node.kind(),
        node.text(),
        node.children().stream().map(child -> qualify(child, scope)).toList());
  }

  private static Field reference(String value, String scope) {
    String[] parts = value.split("\\.", -1);
    if (parts.length == 1) {
      identifier(value);
      return new Field(scope, value);
    }
    if (parts.length != 2 || !scope.equals("parent") && !parts[0].equals("parent")) {
      throw new FormulaException("UNKNOWN_FIELD");
    }
    identifier(parts[0]);
    identifier(parts[1]);
    return new Field(parts[0], parts[1]);
  }

  private static void identifier(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 128
        || value.contains(".")
        || value.contains("\n")
        || value.contains("\r")) {
      throw new FormulaException("FIELD_ID");
    }
  }

  private static int nodes(FormulaNode node) {
    return 1 + node.children().stream().mapToInt(FormulaAggregatePlan::nodes).sum();
  }

  private static Map<String, Object> snapshot(Scope scope, Map<String, ?> input) {
    if (input.keySet().stream().anyMatch(scope.formulas()::containsKey)) {
      throw new FormulaException("COMPUTED_WRITE");
    }
    if (!scope.inputs().containsAll(input.keySet())) {
      throw new FormulaException("UNKNOWN_FIELD");
    }
    var result = new LinkedHashMap<String, Object>();
    result.putAll(input);
    return result;
  }

  private static Object required(Map<String, Object> values, String key) {
    if (!values.containsKey(key)) {
      throw new FormulaException("UNKNOWN_FIELD");
    }
    return values.get(key);
  }

  private static Map<String, Object> immutable(Map<String, Object> values) {
    return Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }
}
