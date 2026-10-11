package org.simplepoint.plugin.dna.runtime.api.record;

/** Bounded database-neutral operators accepted by the Runtime Record API. */
public enum RecordFilterOperator {
  AND,
  OR,
  NOT,
  EQUALS,
  NOT_EQUALS,
  IN,
  NOT_IN,
  LIKE,
  NOT_LIKE,
  GREATER_THAN,
  LESS_THAN,
  GREATER_THAN_OR_EQUAL,
  LESS_THAN_OR_EQUAL,
  BETWEEN,
  NOT_BETWEEN,
  IS_NULL,
  IS_NOT_NULL;

  /** Returns whether this operator combines child filters. */
  public boolean group() {
    return this == AND || this == OR || this == NOT;
  }
}
