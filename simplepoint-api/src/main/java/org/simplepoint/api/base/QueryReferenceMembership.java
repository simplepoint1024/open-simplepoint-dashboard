package org.simplepoint.api.base;

import java.util.Map;
import java.util.Objects;

/**
 * Server-owned EXISTS restriction. It never exposes a relationship or entity name to public queries.
 * References are resolved only against registered JPA entities; deleted references never match.
 *
 * @param field outer scalar string member
 * @param entityName registered reference entity name, selected by server code
 * @param referenceField reference scalar string member
 * @param equalities fixed literal reference conditions, never request-supplied filter expressions
 * @param included true for EXISTS, false for NOT EXISTS
 */
public record QueryReferenceMembership(
    String field, String entityName, String referenceField, Map<String, Object> equalities,
    boolean included) {
  public QueryReferenceMembership {
    Objects.requireNonNull(field, "field");
    Objects.requireNonNull(entityName, "entityName");
    Objects.requireNonNull(referenceField, "referenceField");
    equalities = Map.copyOf(equalities);
  }
}
