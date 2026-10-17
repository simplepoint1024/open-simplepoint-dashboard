package org.simplepoint.calculation;

import static org.simplepoint.calculation.FormulaValues.MATH;
import static org.simplepoint.calculation.FormulaValues.bool;
import static org.simplepoint.calculation.FormulaValues.bounded;
import static org.simplepoint.calculation.FormulaValues.number;
import static org.simplepoint.calculation.FormulaValues.text;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Thread-safe independent evaluator. Compiled formulas are immutable and reusable. */
public final class FormulaEngine {
  private final Map<String, FormulaFunction> functions;
  private final Clock clock;

  /** Creates the standard registry with the UTC system clock. */
  public FormulaEngine() {
    this(StandardFunctions.all(), Clock.systemUTC());
  }

  /** Creates an isolated immutable registry and an application-supplied clock. */
  public FormulaEngine(List<FormulaFunction> functions, Clock clock) {
    var registry = new LinkedHashMap<String, FormulaFunction>();
    functions.stream()
        .sorted(java.util.Comparator.comparing(FormulaFunction::name))
        .forEach(
            function -> {
              if (registry.putIfAbsent(function.name(), function) != null) {
                throw new FormulaException("DUPLICATE_FUNCTION");
              }
            });
    this.functions = Collections.unmodifiableMap(registry);
    this.clock = Objects.requireNonNull(clock);
  }

  Clock snapshotClock() {
    return Clock.fixed(clock.instant(), clock.getZone());
  }

  /** Returns the stable sorted function catalog. */
  public List<FormulaFunction> functions() {
    return List.copyOf(functions.values());
  }

  /** Parses and validates an expression without evaluating it. */
  public CompiledFormula compile(String expression) {
    return compile(FormulaSyntax.parse(expression));
  }

  /** Validates a visual tree through the same grammar and function registry. */
  public CompiledFormula compile(FormulaNode node) {
    // Apply exactly the text grammar to externally supplied visual ASTs as well.
    if (!node.equals(FormulaSyntax.parse(FormulaSyntax.print(node)))) {
      throw new FormulaException("INVALID_AST");
    }
    validate(node);
    return new CompiledFormula(node, FormulaSyntax.references(node));
  }

  private void validate(FormulaNode node) {
    if (node.kind() == FormulaNode.Kind.NUMBER) {
      number(node.text());
    }
    if (node.kind() == FormulaNode.Kind.CALL) {
      FormulaFunction function = functions.get(node.text());
      if (function == null) {
        throw new FormulaException("UNKNOWN_FUNCTION");
      }
      if (node.children().size() < function.minArguments()
          || node.children().size() > function.maxArguments()) {
        throw new FormulaException("ARGUMENT_COUNT");
      }
    }
    node.children().forEach(this::validate);
  }

  /** Immutable expression compiled against this engine registry. */
  public final class CompiledFormula {
    private final FormulaNode tree;
    private final Set<String> references;

    private CompiledFormula(FormulaNode tree, Set<String> references) {
      this.tree = tree;
      this.references = references;
    }

    /** Returns the immutable syntax tree. */
    public FormulaNode tree() {
      return tree;
    }

    /** Returns canonical expression text. */
    public String expression() {
      return FormulaSyntax.print(tree);
    }

    /** Returns every field reference, including references in conditional branches. */
    public Set<String> references() {
      return references;
    }

    /** Evaluates against explicit values; missing fields are distinct from null. */
    public Object evaluate(Map<String, ?> values) {
      return evaluate(
          field -> {
            if (!values.containsKey(field)) {
              throw new FormulaException("UNKNOWN_FIELD");
            }
            return values.get(field);
          });
    }

    /**
     * Resolver is supplied by the owning application; absent fields must be distinguished from
     * null.
     */
    public Object evaluate(Function<String, Object> resolver) {
      return evaluate(resolver, snapshotClock());
    }

    Object evaluate(Function<String, Object> resolver, Clock at) {
      return new Evaluation(resolver, at).value(tree);
    }
  }

  private final class Evaluation {
    private final Function<String, Object> resolver;
    private final Clock evaluationClock;
    private final Map<String, Object> resolved = new LinkedHashMap<>();
    private int steps;
    private int items;
    private int characters;

    private Evaluation(Function<String, Object> resolver, Clock clock) {
      this.resolver = Objects.requireNonNull(resolver);
      this.evaluationClock = clock;
    }

    private Object value(FormulaNode node) {
      if (++steps > 10000) {
        throw new FormulaException("EVALUATION_LIMIT");
      }
      try {
        Object result =
            switch (node.kind()) {
              case NUMBER -> number(node.text());
              case STRING -> node.text();
              case BOOLEAN -> node.text().equals("TRUE");
              case NULL -> null;
              case FIELD -> {
                if (!resolved.containsKey(node.text())) {
                  resolved.put(node.text(), checked(resolver.apply(node.text()), true));
                }
                yield resolved.get(node.text());
              }
              case UNARY -> {
                BigDecimal operand = number(value(node.children().getFirst()));
                yield
                  switch (node.text()) {
                    case "+" -> operand;
                    case "-" -> operand.negate(MATH);
                    case "%" -> operand.divide(BigDecimal.valueOf(100), MATH);
                    default -> throw new FormulaException("INVALID_AST");
                  };
              }
              case BINARY ->
                  binary(
                      node.text(),
                      value(node.children().getFirst()),
                      value(node.children().get(1)));
              case CALL ->
                  functions
                      .get(node.text())
                      .body()
                      .apply(
                          new FormulaFunction.Arguments() {
                            private final Object[] cache = new Object[node.children().size()];
                            private final boolean[] loaded = new boolean[cache.length];

                            @Override
                            public int size() {
                              return cache.length;
                            }

                            @Override
                            public Clock clock() {
                              return evaluationClock;
                            }

                            @Override
                            public Object get(int index) {
                              if (!loaded[index]) {
                                cache[index] = value(node.children().get(index));
                                loaded[index] = true;
                              }
                              return cache[index];
                            }
                          });
            };
        return checked(result, true);
      } catch (ArithmeticException failure) {
        throw new FormulaException("NUMBER_RANGE");
      } catch (DateTimeException failure) {
        throw new FormulaException("DATE");
      }
    }

    private Object checked(Object value, boolean allowList) {
      if (++items > 100000) {
        throw new FormulaException("EVALUATION_LIMIT");
      }
      if (value == null
          || value instanceof Boolean
          || value instanceof LocalDate
          || value instanceof LocalDateTime
          || value instanceof LocalTime) {
        return value;
      }
      if (value instanceof Number) {
        return number(value);
      }
      if (value instanceof String string) {
        characters += string.length();
        if (characters > 1048576) {
          throw new FormulaException("EVALUATION_LIMIT");
        }
        return bounded(string);
      }
      if (allowList && value instanceof List<?> list) {
        if (list.size() > 10000) {
          throw new FormulaException("VALUE_SIZE");
        }
        var snapshot = new ArrayList<Object>(list.size());
        for (Object item : list) {
          snapshot.add(checked(item, false));
        }
        return Collections.unmodifiableList(snapshot);
      }
      throw new FormulaException("TYPE");
    }
  }

  private static Object binary(String operator, Object left, Object right) {
    return switch (operator) {
      case "&" -> bounded(text(left) + text(right));
      case "+" -> number(left).add(number(right), MATH);
      case "-" -> number(left).subtract(number(right), MATH);
      case "*" -> number(left).multiply(number(right), MATH);
      case "/" -> {
        BigDecimal divisor = number(right);
        if (divisor.signum() == 0) {
          throw new FormulaException("DIV_ZERO");
        }
        yield number(left).divide(divisor, MATH);
      }
      case "^" -> StandardFunctions.power(number(left), number(right));
      case "=", "<>", "<", "<=", ">", ">=" -> {
        int comparison = compare(left, right);
        yield
          switch (operator) {
            case "=" -> comparison == 0;
            case "<>" -> comparison != 0;
            case "<" -> comparison < 0;
            case "<=" -> comparison <= 0;
            case ">" -> comparison > 0;
            default -> comparison >= 0;
          };
      }
      default -> throw new FormulaException("INVALID_AST");
    };
  }

  private static int compare(Object left, Object right) {
    if (left == null && right == null) {
      return 0;
    }
    if (left instanceof Number || right instanceof Number) {
      return number(left).compareTo(number(right));
    }
    if (left instanceof Boolean || right instanceof Boolean) {
      return Boolean.compare(bool(left), bool(right));
    }
    if (left instanceof LocalDate a && right instanceof LocalDate b) {
      return a.compareTo(b);
    }
    if (left instanceof LocalDateTime a && right instanceof LocalDateTime b) {
      return a.compareTo(b);
    }
    if (left instanceof LocalTime a && right instanceof LocalTime b) {
      return a.compareTo(b);
    }
    return text(left).compareToIgnoreCase(text(right));
  }
}
