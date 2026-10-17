package org.simplepoint.calculation;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/** Explicit extension point. Implementations must be bounded and side-effect free. */
public record FormulaFunction(
    String name,
    String category,
    int minArguments,
    int maxArguments,
    String resultType,
    List<String> parameters,
    Body body) {
  /** Validates the extension descriptor and copies parameter names. */
  public FormulaFunction {
    if (name == null
        || !name.matches("[A-Z_][A-Z_0-9.]*")
        || minArguments < 0
        || maxArguments < minArguments
        || maxArguments > 256) {
      throw new IllegalArgumentException("Invalid function descriptor");
    }
    parameters = List.copyOf(parameters);
    Objects.requireNonNull(body);
  }

  /** Side-effect-free implementation registered by a trusted host. */
  @FunctionalInterface
  public interface Body {
    /** Evaluates the function using memoized lazy arguments. */
    Object apply(Arguments arguments);
  }

  /** Arguments are memoized and lazy, so IF and IFERROR do not evaluate unused branches. */
  public interface Arguments {
    /** Returns the number of supplied arguments. */
    int size();

    /** Evaluates and memoizes one positional argument. */
    Object get(int index);

    /** Returns the fixed clock for this evaluation. */
    Clock clock();
  }
}
