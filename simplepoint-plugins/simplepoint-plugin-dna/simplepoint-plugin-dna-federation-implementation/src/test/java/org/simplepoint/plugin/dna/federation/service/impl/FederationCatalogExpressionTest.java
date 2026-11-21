package org.simplepoint.plugin.dna.federation.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException.Code;

class FederationCatalogExpressionTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "lower((name)::text)",
        "((amount > 0) AND (amount < 100))",
        "('中文'::character varying)",
        "CURRENT_TIMESTAMP",
        "now()",
        "gen_random_uuid()",
        "((price * quantity))",
        "(id IS NOT NULL)",
        "'2026-10-28'::date"
      })
  void parsesNativePostgresCatalogExpressionsWithoutExecutingThem(String expression) {
    assertDoesNotThrow(() -> FederationCatalogExpression.verify(expression, true));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "(lower(`name`))",
        "current_timestamp(6)",
        "(uuid_to_bin(uuid()))",
        "((`price` * `quantity`))",
        "(`id` > 0)"
      })
  void parsesNativeMysqlCatalogExpressionsWithoutExecutingThem(String expression) {
    assertDoesNotThrow(() -> FederationCatalogExpression.verify(expression, false));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "evil()",
        "public.lower(name)",
        "amount::custom_type",
        "set_config('search_path','public',false)",
        "nextval('unowned_seq'::regclass)",
        "(SELECT amount FROM hidden)",
        "1; DROP TABLE hidden"
      })
  void unprovenFunctionsTypesSequencesAndQueriesFailClosed(String expression) {
    var failure =
        assertThrows(
            FederationSqlWriteException.class,
            () -> FederationCatalogExpression.verify(expression, true));
    assertEquals(Code.SQL_WRITE_UNSUPPORTED, failure.errorCode());
  }
}
