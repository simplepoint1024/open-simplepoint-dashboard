package org.simplepoint.data.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class PostgresSchemaContractTest {
  @Test
  void dumpRestoreCastDistributionPreservesExactLiteralMembership() {
    String original = "CHECK (((type)::text = ANY ((ARRAY['A'::character varying, "
        + "'B,C''D'::character varying])::text[])))";
    String restored = "CHECK (((type)::text = ANY (ARRAY[('A'::character varying)::text, "
        + "('B,C''D'::character varying)::text])))";
    assertEquals(PostgresSchemaContract.normalizeConstraint(original),
        PostgresSchemaContract.normalizeConstraint(restored));
    assertNotEquals(PostgresSchemaContract.normalizeConstraint(original),
        PostgresSchemaContract.normalizeConstraint(restored.replace("'A'", "'CHANGED'")));
    assertNotEquals(PostgresSchemaContract.normalizeConstraint(original),
        PostgresSchemaContract.normalizeConstraint(restored.replace("(type)", "(other)")));
  }

  @Test
  void unsupportedExpressionsAreNeverTreatedAsEquivalent() {
    for (String expression : new String[] {
        "CHECK (type IS NOT NULL)",
        "CHECK (((type)::text = ANY (ARRAY[('A'::character varying)::text, NULL])))",
        "CHECK (((type)::text = ANY (ARRAY[('A'::character varying)::text, ])))",
        "CHECK (value = 'ARRAY[(''A''::character varying)::text]')"
    }) {
      assertEquals(expression, PostgresSchemaContract.normalizeConstraint(expression));
    }
  }

  @Test
  void compositeChecksAndPartialIndexPredicatesRetainTheirOperators() {
    String original = "btree (id) WHERE ((status)::text <> ALL "
        + "((ARRAY['A'::character varying, 'B'::character varying])::text[])) AND active";
    String restored = "btree (id) WHERE ((status)::text <> ALL "
        + "(ARRAY[('A'::character varying)::text, ('B'::character varying)::text])) AND active";
    assertEquals(original, PostgresSchemaContract.normalizeConstraint(restored));
    assertEquals(original, PostgresSchemaContract.normalizeConstraint(original));
    assertNotEquals(original, PostgresSchemaContract.normalizeConstraint(restored.replace("<> ALL", "= ANY")));
  }
}
