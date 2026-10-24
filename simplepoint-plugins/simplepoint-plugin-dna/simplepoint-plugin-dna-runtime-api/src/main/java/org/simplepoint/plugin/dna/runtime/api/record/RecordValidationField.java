package org.simplepoint.plugin.dna.runtime.api.record;

/** Stable field identity in a published aggregate; never a physical column or SQL expression. */
public record RecordValidationField(String scope, String fieldId) {
  /** The parent scope is named parent; detail scopes use their declared stable identities. */
  public RecordValidationField {
    RecordCalculationDefinition.identity(scope);
    RecordCalculationDefinition.identity(fieldId);
  }
}
