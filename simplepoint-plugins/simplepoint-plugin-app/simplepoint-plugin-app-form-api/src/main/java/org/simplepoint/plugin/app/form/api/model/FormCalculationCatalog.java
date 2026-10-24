package org.simplepoint.plugin.app.form.api.model;

import java.util.List;

/** Versioned public function metadata; no executable function body crosses the transport. */
public record FormCalculationCatalog(String languageVersion, List<Function> functions) {
  /** Retains an immutable descriptor list. */
  public FormCalculationCatalog {
    functions = List.copyOf(functions);
  }

  /** Function palette contract shared by text and visual editors. */
  public record Function(
      String name,
      String category,
      int minArguments,
      int maxArguments,
      String resultType,
      List<String> parameters) {
    /** Copies parameter labels without exposing evaluator implementation types. */
    public Function {
      parameters = List.copyOf(parameters);
    }
  }
}
