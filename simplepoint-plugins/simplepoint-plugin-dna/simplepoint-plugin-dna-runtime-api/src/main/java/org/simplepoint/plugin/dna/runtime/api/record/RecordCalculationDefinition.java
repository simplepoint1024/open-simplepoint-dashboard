package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Versioned publication metadata, never a record-write argument. Expressions use stable field
 * identities; storage columns and dependencies are resolved and compiled by DNA, not the caller.
 * An explicit empty definition removes prior formulas; an absent publication property preserves
 * the legacy wire contract and must never implicitly remove an installed definition.
 */
public record RecordCalculationDefinition(
    String languageVersion,
    Map<String, String> formulas,
    Map<String, RecordCalculationScope> details,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    Map<String, RecordFieldConstraints> constraints,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    List<RecordValidationRule> validations,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    RecordStateTransitions stateTransitions,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    List<RecordFieldWriteRule> writeRules) {

  private static final int MAX_FORMULAS = 256;
  private static final int MAX_EXPRESSION_LENGTH = 8192;
  private static final int MAX_TOTAL_EXPRESSION_LENGTH = 65_536;

  /** Existing immutable definitions preserve absence of business-constraint metadata. */
  public RecordCalculationDefinition(final String languageVersion, final Map<String, String> formulas,
      final Map<String, RecordCalculationScope> details) {
    this(languageVersion, formulas, details, null);
  }

  /** Existing definitions preserve absence of aggregate validation metadata. */
  public RecordCalculationDefinition(final String languageVersion, final Map<String, String> formulas,
      final Map<String, RecordCalculationScope> details, final Map<String, RecordFieldConstraints> constraints) {
    this(languageVersion, formulas, details, constraints, null);
  }

  /** Existing definitions retain absent lifecycle metadata and their original checksums. */
  public RecordCalculationDefinition(final String languageVersion, final Map<String, String> formulas,
      final Map<String, RecordCalculationScope> details, final Map<String, RecordFieldConstraints> constraints,
      final List<RecordValidationRule> validations) {
    this(languageVersion, formulas, details, constraints, validations, null);
  }

  /** Existing definitions retain absent operation rules and their original checksums. */
  public RecordCalculationDefinition(final String languageVersion, final Map<String, String> formulas,
      final Map<String, RecordCalculationScope> details, final Map<String, RecordFieldConstraints> constraints,
      final List<RecordValidationRule> validations, final RecordStateTransitions stateTransitions) {
    this(languageVersion, formulas, details, constraints, validations, stateTransitions, null);
  }

  /** Bounds the entire publication, rejects ambiguous scopes and copies every caller-owned map. */
  public RecordCalculationDefinition {
    if (!"1".equals(languageVersion)) {
      throw new IllegalArgumentException("Unsupported calculation language version");
    }
    formulas = formulas(formulas);
    constraints = constraints(constraints);
    validations = validations == null ? null : List.copyOf(validations);
    writeRules = writeRules == null ? null : List.copyOf(writeRules);
    if (writeRules != null) {
      Set<RecordValidationField> targets = new HashSet<>();
      if (writeRules.size() > 768 || writeRules.stream().anyMatch(rule -> !targets.add(rule.field()))) {
        throw new IllegalArgumentException("Field write admission identity or budget exceeded");
      }
    }
    if (validations != null) {
      Set<String> identities = new HashSet<>();
      if (validations.size() > 2000 || validations.stream().anyMatch(rule -> !identities.add(rule.ruleId()))
          || validations.stream().mapToLong(rule -> rule.when().complexity()).sum() > 16_384
          || validations.stream().mapToLong(rule -> rule.when().textBudget()).sum() > 65_536) {
        throw new IllegalArgumentException("Aggregate validation identity or budget exceeded");
      }
    }
    details = Map.copyOf(Objects.requireNonNull(details, "details"));
    if (details.size() > 5) {
      throw new IllegalArgumentException("Calculation detail scope budget exceeded");
    }
    long ruleBudget = constraints == null ? 0 : constraints.values().stream().mapToLong(RecordFieldConstraints::textBudget).sum();
    ruleBudget += stateTransitions == null ? 0 : stateTransitions.textBudget();
    ruleBudget += writeRules == null ? 0 : writeRules.stream().mapToLong(RecordFieldWriteRule::textBudget).sum();
    ruleBudget += (validations == null ? List.<RecordValidationRule>of() : validations).stream().filter(rule -> rule.constraints() != null)
        .mapToLong(rule -> rule.constraints().textBudget()).sum();
    Set<String> models = new HashSet<>();
    int count = formulas.size();
    long length = expressionLength(formulas);
    for (var entry : details.entrySet()) {
      identity(entry.getKey());
      if ("parent".equals(entry.getKey()) || !models.add(entry.getValue().physicalModelId())) {
        throw new IllegalArgumentException("Calculation scopes must have unique, unreserved identities");
      }
      count += entry.getValue().formulas().size();
      ruleBudget += entry.getValue().constraintsOrEmpty().values().stream().mapToLong(RecordFieldConstraints::textBudget).sum();
      length += expressionLength(entry.getValue().formulas());
    }
    if (count > MAX_FORMULAS || length > MAX_TOTAL_EXPRESSION_LENGTH || ruleBudget > 65_536) {
      throw new IllegalArgumentException("Aggregate calculation definition budget exceeded");
    }
  }

  /** Whether the installed version has no calculations or business constraints in any scope. */
  @com.fasterxml.jackson.annotation.JsonIgnore
  public boolean isEmpty() {
    return stateTransitions == null && writeRulesOrEmpty().isEmpty() && formulas.isEmpty()
        && constraintsOrEmpty().isEmpty() && validationsOrEmpty().isEmpty()
        && details.values().stream().allMatch(scope -> scope.formulas().isEmpty() && scope.constraintsOrEmpty().isEmpty());
  }

  /** Resolves missing legacy metadata without changing its serialized shape. */
  public Map<String, RecordFieldConstraints> constraintsOrEmpty() {
    return constraints == null ? Map.of() : constraints;
  }

  /** Resolves legacy absence without adding metadata to its canonical serialization. */
  public List<RecordValidationRule> validationsOrEmpty() {
    return validations == null ? List.of() : validations;
  }

  /** Legacy definitions retain their original canonical JSON. */
  public List<RecordFieldWriteRule> writeRulesOrEmpty() {
    return writeRules == null ? List.of() : writeRules;
  }

  static Map<String, RecordFieldConstraints> constraints(final Map<String, RecordFieldConstraints> source) {
    if (source == null) {
      return null;
    }
    // Domain fields and domain-owned control fields share the same neutral validation map.
    if (source.size() > 512) {
      throw new IllegalArgumentException("Business constraint field budget exceeded");
    }
    source.keySet().forEach(RecordCalculationDefinition::identity);
    return Map.copyOf(source);
  }

  static Map<String, String> formulas(final Map<String, String> source) {
    Objects.requireNonNull(source, "formulas");
    if (source.size() > MAX_FORMULAS) {
      throw new IllegalArgumentException("Calculation field budget exceeded");
    }
    source.forEach((field, expression) -> {
      identity(field);
      if (expression == null || expression.isBlank() || expression.length() > MAX_EXPRESSION_LENGTH) {
        throw new IllegalArgumentException("Calculation expression is missing or exceeds its budget");
      }
    });
    return Map.copyOf(source);
  }

  static void identity(final String value) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
      throw new IllegalArgumentException("Calculation identity has an invalid format");
    }
  }

  private static long expressionLength(final Map<String, String> expressions) {
    return expressions.values().stream().mapToLong(String::length).sum();
  }
}
