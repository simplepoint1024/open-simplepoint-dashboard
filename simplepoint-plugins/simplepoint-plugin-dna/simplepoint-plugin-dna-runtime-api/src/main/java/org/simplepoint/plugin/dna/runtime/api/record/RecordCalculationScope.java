package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.Map;
import java.util.Objects;

/** Immutable model binding and stable-field expressions for one owned aggregate scope. */
public record RecordCalculationScope(
    String physicalModelId,
    RecordSchemaReference schemaReference,
    Map<String, String> formulas,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    Map<String, RecordFieldConstraints> constraints) {

  /** Keeps previously published scope JSON and checksums unchanged. */
  public RecordCalculationScope(final String physicalModelId, final RecordSchemaReference schemaReference,
      final Map<String, String> formulas) {
    this(physicalModelId, schemaReference, formulas, null);
  }

  /** Validates wire budgets; the trusted resolver must verify the model, schema and ownership. */
  public RecordCalculationScope {
    physicalModelId = RecordQueryLimits.modelId(physicalModelId);
    Objects.requireNonNull(schemaReference, "schemaReference");
    formulas = RecordCalculationDefinition.formulas(formulas);
    constraints = RecordCalculationDefinition.constraints(constraints);
  }

  /** Resolves legacy absence without introducing properties into immutable JSON. */
  public Map<String, RecordFieldConstraints> constraintsOrEmpty() {
    return constraints == null ? Map.of() : constraints;
  }
}
