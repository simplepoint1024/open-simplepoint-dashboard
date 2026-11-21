package org.simplepoint.plugin.dna.federation.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException.Code;

class FederationWriteExpressionPolicyTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "UPDATE ds.t SET amount=ABS(amount)+ROUND(2.25,1) WHERE id=1",
        "INSERT INTO ds.t SELECT id,COALESCE(SUM(amount),0) FROM ds.s GROUP BY id",
        "INSERT INTO ds.t SELECT ROW_NUMBER() OVER (ORDER BY id),LENGTH(name) FROM ds.s",
        "UPDATE ds.t SET name=LOWER(CAST(id AS VARCHAR(32))) WHERE id=1",
        "MERGE INTO ds.t t USING ds.s s ON t.id=s.id WHEN MATCHED THEN UPDATE SET"
            + " amount=ABS(s.amount)"
      })
  void ordinaryScalarAggregateWindowAndConversionExpressionsRemainAvailable(String sql) {
    assertDoesNotThrow(() -> FederationDmlPlan.parse(sql));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "UPDATE ds.t SET amount=evil(id)",
        "UPDATE ds.t SET name=pg_catalog.lower(name)",
        "UPDATE ds.t SET name=\"lower\"(name)",
        "UPDATE ds.t SET amount=(SELECT evil(id) FROM ds.s)",
        "INSERT INTO ds.t VALUES (1,NEXT VALUE FOR sequence_name)",
        "UPDATE ds.t SET amount=CAST(amount AS custom_type)",
        "MERGE INTO ds.t t USING ds.s s ON t.id=s.id WHEN MATCHED THEN UPDATE SET amount=evil(s.id)"
      })
  void unknownOrExplicitlyReboundRoutinesAndCustomTypesAreRejected(String sql) {
    var failure =
        assertThrows(FederationSqlWriteException.class, () -> FederationDmlPlan.parse(sql));
    assertEquals(Code.SQL_WRITE_UNSUPPORTED, failure.errorCode());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "CREATE TABLE ds.t (id INT DEFAULT (ABS(-1)))",
        "CREATE TABLE ds.t (id INT CHECK (id>0))",
        "CREATE TABLE ds.t AS SELECT id,LOWER(name) AS name FROM ds.s",
        "ALTER TABLE ds.t ALTER COLUMN id SET DEFAULT (CAST(1 AS INTEGER))"
      })
  void boundedDdlExpressionsRetainStandardBuiltins(String sql) {
    assertDoesNotThrow(() -> FederationDdlPlan.parse(sql));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "CREATE TABLE ds.t (id INT DEFAULT (evil()))",
        "CREATE TABLE ds.t (id INT CHECK (evil(id)>0))",
        "CREATE TABLE ds.t AS SELECT evil(id) AS id FROM ds.s",
        "ALTER TABLE ds.t ALTER COLUMN id SET DEFAULT (CAST(1 AS custom_type))"
      })
  void unknownRoutinesCannotHideInDdlExpressionsOrCreationQueries(String sql) {
    var failure =
        assertThrows(FederationSqlWriteException.class, () -> FederationDdlPlan.parse(sql));
    assertEquals(Code.SQL_WRITE_UNSUPPORTED, failure.errorCode());
  }
}
