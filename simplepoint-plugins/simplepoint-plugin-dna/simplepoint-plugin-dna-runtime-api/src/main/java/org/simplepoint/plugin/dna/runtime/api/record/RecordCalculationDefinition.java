package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Versioned record governance metadata, never a record-write argument. The historical name and wire
 * property are retained; reference admission is independent of the formula engine. Expressions use
 * stable field identities; storage columns and dependencies are resolved and compiled by DNA, not
 * the caller. An explicit empty definition removes prior formulas; an absent publication property
 * preserves the legacy wire contract and must never implicitly remove an installed definition.
 */
public record RecordCalculationDefinition(
    String languageVersion,
    Map<String, String> formulas,
    Map<String, RecordCalculationScope> details,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        Map<String, RecordFieldConstraints> constraints,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        List<RecordValidationRule> validations,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        RecordStateTransitions stateTransitions,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        List<RecordFieldWriteRule> writeRules,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        List<RecordReferenceRule> references,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        List<RecordCollectionRule> collections) {

  private static final int MAX_FORMULAS = 256;
  private static final int MAX_EXPRESSION_LENGTH = 8192;
  private static final int MAX_TOTAL_EXPRESSION_LENGTH = 65_536;

  /** Old immutable policies preserve absence of the collection declaration. */
  public RecordCalculationDefinition(
      String languageVersion,
      Map<String, String> formulas,
      Map<String, RecordCalculationScope> details,
      Map<String, RecordFieldConstraints> constraints,
      List<RecordValidationRule> validations,
      RecordStateTransitions stateTransitions,
      List<RecordFieldWriteRule> writeRules,
      List<RecordReferenceRule> references) {
    this(
        languageVersion,
        formulas,
        details,
        constraints,
        validations,
        stateTransitions,
        writeRules,
        references,
        null);
  }

  /** Historical governance definitions keep the original JSON and checksum. */
  public RecordCalculationDefinition(
      final String languageVersion,
      final Map<String, String> formulas,
      final Map<String, RecordCalculationScope> details,
      final Map<String, RecordFieldConstraints> constraints,
      final List<RecordValidationRule> validations,
      final RecordStateTransitions stateTransitions,
      final List<RecordFieldWriteRule> writeRules) {
    this(
        languageVersion,
        formulas,
        details,
        constraints,
        validations,
        stateTransitions,
        writeRules,
        null);
  }

  /** Existing immutable definitions preserve absence of business-constraint metadata. */
  public RecordCalculationDefinition(
      final String languageVersion,
      final Map<String, String> formulas,
      final Map<String, RecordCalculationScope> details) {
    this(languageVersion, formulas, details, null);
  }

  /** Existing definitions preserve absence of aggregate validation metadata. */
  public RecordCalculationDefinition(
      final String languageVersion,
      final Map<String, String> formulas,
      final Map<String, RecordCalculationScope> details,
      final Map<String, RecordFieldConstraints> constraints) {
    this(languageVersion, formulas, details, constraints, null);
  }

  /** Existing definitions retain absent lifecycle metadata and their original checksums. */
  public RecordCalculationDefinition(
      final String languageVersion,
      final Map<String, String> formulas,
      final Map<String, RecordCalculationScope> details,
      final Map<String, RecordFieldConstraints> constraints,
      final List<RecordValidationRule> validations) {
    this(languageVersion, formulas, details, constraints, validations, null);
  }

  /** Existing definitions retain absent operation rules and their original checksums. */
  public RecordCalculationDefinition(
      final String languageVersion,
      final Map<String, String> formulas,
      final Map<String, RecordCalculationScope> details,
      final Map<String, RecordFieldConstraints> constraints,
      final List<RecordValidationRule> validations,
      final RecordStateTransitions stateTransitions) {
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
    references = references == null ? null : List.copyOf(references);
    collections = RecordCollectionRule.canonical(collections, references);
    if (references != null) {
      Set<String> identities = new HashSet<>();
      Set<RecordValidationField> sources = new HashSet<>();
      Set<RecordValidationField> mapped = new HashSet<>();
      int mappingCount = 0;
      for (var rule : references) {
        mappingCount += rule.mappings().size();
        for (var mapping : rule.mappings()) {
          var destination =
              new RecordValidationField(rule.source().scope(), mapping.destinationFieldId());
          if (!mapped.add(destination)
              || references.stream().anyMatch(other -> other.source().equals(destination))) {
            throw new IllegalArgumentException(
                "Reference value providers conflict within their row scope");
          }
        }
      }
      if (mappingCount > 128) {
        throw new IllegalArgumentException("Reference mapping aggregate budget exceeded");
      }
      if (references.size() > 50
          || references.stream()
              .anyMatch(rule -> !identities.add(rule.relationId()) || !sources.add(rule.source()))
          || references.stream().mapToLong(RecordReferenceRule::complexity).sum() > 16_384
          || references.stream().mapToLong(RecordReferenceRule::textBudget).sum() > 65_536) {
        throw new IllegalArgumentException("Reference policy identity or budget exceeded");
      }
    }
    if (writeRules != null) {
      Set<RecordValidationField> targets = new HashSet<>();
      if (writeRules.size() > 768
          || writeRules.stream().anyMatch(rule -> !targets.add(rule.field()))) {
        throw new IllegalArgumentException("Field write admission identity or budget exceeded");
      }
    }
    if (validations != null) {
      Set<String> identities = new HashSet<>();
      if (validations.size() > 2000
          || validations.stream().anyMatch(rule -> !identities.add(rule.ruleId()))
          || validations.stream().mapToLong(rule -> rule.when().complexity()).sum() > 16_384
          || validations.stream().mapToLong(rule -> rule.when().textBudget()).sum() > 65_536) {
        throw new IllegalArgumentException("Aggregate validation identity or budget exceeded");
      }
    }
    details = Map.copyOf(Objects.requireNonNull(details, "details"));
    var detailModels =
        details.values().stream()
            .map(RecordCalculationScope::physicalModelId)
            .collect(java.util.stream.Collectors.toSet());
    if (collections != null
        && collections.stream()
            .anyMatch(
                rule ->
                    detailModels.contains(rule.edgeModelId())
                        || detailModels.contains(rule.targetModelId()))) {
      throw new IllegalArgumentException("Collection storage and targets cannot be owned details");
    }
    for (var rule : references == null ? List.<RecordReferenceRule>of() : references) {
      if (rule.mappings().isEmpty()) {
        continue;
      }
      String scope = rule.source().scope();
      if (!"parent".equals(scope) && !details.containsKey(scope)) {
        throw new IllegalArgumentException("Mapped reference owner scope is absent");
      }
      var calculated = "parent".equals(scope) ? formulas : details.get(scope).formulas();
      if (rule.mappings().stream()
          .anyMatch(mapping -> calculated.containsKey(mapping.destinationFieldId()))) {
        throw new IllegalArgumentException(
            "A field cannot be provided by both a reference mapping and a formula");
      }
    }
    if (details.size() > 5) {
      throw new IllegalArgumentException("Calculation detail scope budget exceeded");
    }
    long ruleBudget =
        constraints == null
            ? 0
            : constraints.values().stream().mapToLong(RecordFieldConstraints::textBudget).sum();
    ruleBudget += stateTransitions == null ? 0 : stateTransitions.textBudget();
    ruleBudget +=
        writeRules == null
            ? 0
            : writeRules.stream().mapToLong(RecordFieldWriteRule::textBudget).sum();
    ruleBudget +=
        (validations == null ? List.<RecordValidationRule>of() : validations)
            .stream()
                .filter(rule -> rule.constraints() != null)
                .mapToLong(rule -> rule.constraints().textBudget())
                .sum();
    Set<String> models = new HashSet<>();
    int count = formulas.size();
    long length = expressionLength(formulas);
    for (var entry : details.entrySet()) {
      identity(entry.getKey());
      if ("parent".equals(entry.getKey()) || !models.add(entry.getValue().physicalModelId())) {
        throw new IllegalArgumentException(
            "Calculation scopes must have unique, unreserved identities");
      }
      count += entry.getValue().formulas().size();
      ruleBudget +=
          entry.getValue().constraintsOrEmpty().values().stream()
              .mapToLong(RecordFieldConstraints::textBudget)
              .sum();
      length += expressionLength(entry.getValue().formulas());
    }
    if (count > MAX_FORMULAS || length > MAX_TOTAL_EXPRESSION_LENGTH || ruleBudget > 65_536) {
      throw new IllegalArgumentException("Aggregate calculation definition budget exceeded");
    }
  }

  /**
   * Whether the installed version has no calculations, constraints or relation rules in any scope.
   */
  @com.fasterxml.jackson.annotation.JsonIgnore
  public boolean isEmpty() {
    return stateTransitions == null
        && writeRulesOrEmpty().isEmpty()
        && referencesOrEmpty().isEmpty()
        && collectionsOrEmpty().isEmpty()
        && formulas.isEmpty()
        && constraintsOrEmpty().isEmpty()
        && validationsOrEmpty().isEmpty()
        && details.values().stream()
            .allMatch(scope -> scope.formulas().isEmpty() && scope.constraintsOrEmpty().isEmpty());
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

  /** Absent legacy policy is not silently converted to an empty serialized declaration. */
  public List<RecordReferenceRule> referencesOrEmpty() {
    return references == null ? List.of() : references;
  }

  /** Keeps legacy JSON absent while presenting a bounded immutable relation collection. */
  public List<RecordCollectionRule> collectionsOrEmpty() {
    return collections == null ? List.of() : collections;
  }

  static Map<String, RecordFieldConstraints> constraints(
      final Map<String, RecordFieldConstraints> source) {
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
    source.forEach(
        (field, expression) -> {
          identity(field);
          if (expression == null
              || expression.isBlank()
              || expression.length() > MAX_EXPRESSION_LENGTH) {
            throw new IllegalArgumentException(
                "Calculation expression is missing or exceeds its budget");
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
