package org.simplepoint.plugin.dna.runtime.api.record;

/** Two-valued inspection predicates, independent of ordinary SQL filter semantics. */
public enum RecordValueConditionOperator {
  TRUE, FALSE, AND, OR, NOT,
  IS_EMPTY, IS_NOT_EMPTY,
  EQUALS, NOT_EQUALS, IN, NOT_IN,
  GREATER_THAN, GREATER_THAN_OR_EQUAL, LESS_THAN, LESS_THAN_OR_EQUAL;

  /** Whether this operator combines child conditions. */
  public boolean group() {
    return this == AND || this == OR || this == NOT;
  }

  /** Whether this node is an explicit constant. */
  public boolean constant() {
    return this == TRUE || this == FALSE;
  }
}
