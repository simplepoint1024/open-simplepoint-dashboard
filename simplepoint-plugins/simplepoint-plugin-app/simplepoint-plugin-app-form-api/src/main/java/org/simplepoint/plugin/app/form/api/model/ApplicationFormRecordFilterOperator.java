package org.simplepoint.plugin.app.form.api.model;

/** Bounded operators accepted by APP runtime record filters. */
public enum ApplicationFormRecordFilterOperator {
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
  IS_NOT_NULL
}
