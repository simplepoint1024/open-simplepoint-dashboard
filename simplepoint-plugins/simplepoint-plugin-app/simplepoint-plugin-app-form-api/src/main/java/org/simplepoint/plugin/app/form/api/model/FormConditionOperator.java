package org.simplepoint.plugin.app.form.api.model;

/** Bounded declarative operators accepted by form rules. */
public enum FormConditionOperator {
  AND,
  OR,
  NOT,
  EQUALS,
  NOT_EQUALS,
  IN,
  NOT_IN,
  GREATER_THAN,
  GREATER_THAN_OR_EQUAL,
  LESS_THAN,
  LESS_THAN_OR_EQUAL,
  IS_EMPTY,
  IS_NOT_EMPTY
}
