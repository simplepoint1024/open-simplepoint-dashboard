package org.simplepoint.plugin.app.form.api.model.relation;

/** Cardinality as viewed from one end of the same stable relationship. */
public enum FormRelationCardinality {
  ONE_TO_ONE,
  MANY_TO_ONE,
  ONE_TO_MANY,
  MANY_TO_MANY;

  /** Reverse views reuse the owning definition instead of creating another relationship. */
  public FormRelationCardinality inverse() {
    return switch (this) {
      case MANY_TO_ONE -> ONE_TO_MANY;
      case ONE_TO_MANY -> MANY_TO_ONE;
      case ONE_TO_ONE, MANY_TO_MANY -> this;
    };
  }
}
