package org.simplepoint.api.base;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Server-owned literal membership restriction, separate from the public filter language.
 *
 * @param field persistent string member selected by server code
 * @param values identifiers obtained from authorized business data
 * @param included true for IN, false for NOT IN
 */
public record QueryMembership(String field, List<String> values, boolean included) {
  /** Freezes a deduplicated snapshot so rows and count share exactly the same scope. */
  public QueryMembership {
    Objects.requireNonNull(field, "field");
    values = List.copyOf(new LinkedHashSet<>(values));
  }
}
