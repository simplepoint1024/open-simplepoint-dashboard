package org.simplepoint.plugin.app.form.api.model;

/** Bounded declarative business validation. Decimal bounds are lossless strings, never expressions. */
public record FormValidationConstraint(
    String constraintId, Kind kind, String fieldId, String otherFieldId,
    String detailId, String detailFieldId, String minimum, String maximum, String message) {
  /** Supported operations over prospective parent values and owned detail rows. */
  public enum Kind { RANGE, AFTER, DETAIL_COUNT, DETAIL_RANGE, DETAIL_SUM }
}
