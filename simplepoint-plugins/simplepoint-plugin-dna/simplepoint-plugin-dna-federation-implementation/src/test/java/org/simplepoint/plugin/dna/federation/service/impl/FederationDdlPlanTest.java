package org.simplepoint.plugin.dna.federation.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException;
import org.simplepoint.plugin.dna.federation.service.impl.FederationDdlPlan.ObjectKind;
import org.simplepoint.plugin.dna.federation.service.impl.FederationDdlPlan.Role;

class FederationDdlPlanTest {
  @Test
  void nativeAdmissionRetainsOperationAndDependencySemantics() {
    var temporary = FederationDdlPlan.parse("CREATE TEMPORARY TABLE ds.t (id INT)");
    assertEquals(FederationDdlPlan.Operation.CREATE, temporary.operation());
    assertTrue(temporary.temporary());
    assertEquals(false, FederationDdlPlan.parse("CREATE UNLOGGED TABLE ds.t (id INT)").temporary());
    assertTrue(FederationDdlPlan.parse("DROP TABLE ds.t CASCADE").cascade());
    var foreign =
        FederationDdlPlan.parse(
            "CREATE TABLE ds.t (id INT REFERENCES ds.parent(id) ON DELETE CASCADE)");
    assertEquals(false, foreign.cascade());
    assertEquals(Role.REFERENCE_TARGET, foreign.objects().getLast().role());
    assertEquals(
        Role.DEPENDENCY,
        FederationDdlPlan.parse("CREATE TABLE ds.t AS SELECT * FROM ds.parent")
            .objects()
            .getLast()
            .role());
    assertEquals(
        FederationDdlPlan.Operation.RENAME,
        FederationDdlPlan.parse("RENAME TABLE ds.t TO ds.u").operation());
  }

  @Test
  void explicitTableCteDoesNotBecomePhysicalDependency() {
    var plan =
        FederationDdlPlan.parse(
            "CREATE TABLE ds.copy AS WITH c AS (SELECT * FROM ds.original) TABLE c");
    assertEquals(
        List.of(List.of("ds", "copy"), List.of("ds", "original")), plan.references().identifiers());
  }

  @Test
  void explicitTableQueryKeepsItsDependency() {
    var plan = FederationDdlPlan.parse("CREATE TABLE ds.copy AS TABLE ds.original");
    assertEquals(
        List.of(List.of("ds", "copy"), List.of("ds", "original")), plan.references().identifiers());
    assertEquals("CREATE TABLE copy AS TABLE original", plan.nativeSql("ds"));
  }

  @Test
  void foreignKeysAndRenameDestinationsArePartOfTheFootprint() {
    var plan =
        FederationDdlPlan.parse(
            "ALTER TABLE ds.orders ADD CONSTRAINT customer_fk "
                + "FOREIGN KEY (tenant_id, customer_id) REFERENCES ds.customers (tenant_id, id) "
                + "ON DELETE RESTRICT, RENAME TO ds.new_orders");
    assertEquals(
        List.of(List.of("ds", "orders"), List.of("ds", "customers"), List.of("ds", "new_orders")),
        plan.references().identifiers());
    assertEquals(
        List.of(Role.TARGET, Role.REFERENCE_TARGET, Role.RENAME_TARGET),
        plan.objects().stream().map(FederationDdlPlan.ObjectReference::role).toList());
    assertEquals(
        "ALTER TABLE orders ADD CONSTRAINT customer_fk FOREIGN KEY (tenant_id, customer_id) "
            + "REFERENCES customers (tenant_id, id) ON DELETE RESTRICT, RENAME TO new_orders",
        plan.nativeSql("ds"));
  }

  @Test
  void quotedDotsEscapesUnicodeAndCommentsArePreserved() {
    var sql =
        "/* header */ CREATE TABLE \"ds\" /* source */ . \"公共.表\" "
            + "(\"名\" VARCHAR(20) DEFAULT 'ds.fake;REFERENCES secret', "
            + "parent INT REFERENCES \"ds\".\"顧客\"\"名\"(id)); -- tail";
    var plan = FederationDdlPlan.parse(sql);
    assertEquals(
        List.of(List.of("ds", "公共.表"), List.of("ds", "顧客\"名")), plan.references().identifiers());
    assertEquals(
        "/* header */ CREATE TABLE \"公共.表\" "
            + "(\"名\" VARCHAR(20) DEFAULT 'ds.fake;REFERENCES secret', "
            + "parent INT REFERENCES \"顧客\"\"名\"(id)); -- tail",
        plan.nativeSql("ds"));
  }

  @Test
  void indexAndColumnCommentsIdentifyTheirOwningTable() {
    var index =
        FederationDdlPlan.parse("CREATE UNIQUE INDEX ds.ix ON ds.orders (id DESC) INCLUDE (name)");
    assertEquals(
        List.of(ObjectKind.INDEX, ObjectKind.TABLE),
        index.objects().stream().map(FederationDdlPlan.ObjectReference::kind).toList());
    assertEquals(Role.INDEX_OWNER, index.objects().getLast().role());
    assertEquals(
        "CREATE UNIQUE INDEX ix ON orders (id DESC) INCLUDE (name)", index.nativeSql("ds"));
    var comment = FederationDdlPlan.parse("COMMENT ON COLUMN ds.public.orders.\"名\" IS '说明'");
    assertEquals(List.of("ds", "public", "orders"), comment.objects().getFirst().names());
    assertEquals("COMMENT ON COLUMN public.orders.\"名\" IS '说明'", comment.nativeSql("ds"));
  }

  @Test
  void createAsAndViewUseCalciteScopedQueryReferences() {
    var sql =
        "CREATE VIEW ds.v AS WITH c AS (SELECT * FROM ds.\"Customers\")\r\n"
            + "SELECT c.id FROM c JOIN\tds.\"Orders\" o ON c.id = o.customer_id";
    var plan = FederationDdlPlan.parse(sql);
    assertEquals(
        List.of(List.of("ds", "v"), List.of("ds", "Customers"), List.of("ds", "Orders")),
        plan.references().identifiers());
    assertEquals(
        "CREATE VIEW v AS WITH c AS (SELECT * FROM \"Customers\")\r\n"
            + "SELECT c.id FROM c JOIN\t\"Orders\" o ON c.id = o.customer_id",
        plan.nativeSql("ds"));
    var as = FederationDdlPlan.parse("CREATE TABLE ds.copy AS SELECT * FROM ds.original");
    assertEquals(2, as.objects().size());
    assertEquals("CREATE TABLE copy AS SELECT * FROM original", as.nativeSql("ds"));
  }

  @Test
  void embeddedQueryDependenciesRetainOriginalSpellingInsteadOfCalciteCaseFolding() {
    var plan = FederationDdlPlan.parse("CREATE TABLE ds.copy AS SELECT id FROM ds.straße");
    assertEquals(List.of("ds", "straße"), plan.objects().getLast().names());
    assertEquals(List.of(false, false), plan.objects().getLast().quoted());
    assertEquals("CREATE TABLE copy AS SELECT id FROM straße", plan.nativeSql("ds"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "CREATE TABLE ds.copy AS WITH straße AS (SELECT id FROM ds.input) SELECT id FROM strasse",
        "CREATE VIEW ds.v AS WITH source AS (SELECT id FROM ds.input) SELECT id FROM \"SOURCE\""
      })
  void ambiguousCteCannotHideDdlQueryDependencies(String sql) {
    assertThrows(FederationSqlWriteException.class, () -> FederationDdlPlan.parse(sql));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "CREATE TABLE ds.t (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, amount"
            + " DECIMAL(18,2) NOT NULL DEFAULT 0.00)",
        "CREATE TABLE ds.t (id INT, parent INT REFERENCES ds.parent(id), CHECK (id > 0))",
        "CREATE TABLE ds.t (id INT, CONSTRAINT pk PRIMARY KEY (id), UNIQUE (id))",
        "CREATE TEMPORARY TABLE ds.t (id INT) ON COMMIT DELETE ROWS",
        "CREATE TABLE ds.t LIKE ds.original",
        "CREATE TABLE ds.t (LIKE ds.original INCLUDING ALL)",
        "CREATE TABLE `ds`.`名``字` (id INT)",
        "CREATE TABLE [ds].[名]]字] (id INT)",
        "CREATE TABLE ds.顧客 (名前 VARCHAR(100))",
        "CREATE OR REPLACE VIEW ds.v (id) AS SELECT id FROM ds.t",
        "CREATE INDEX ds.idx ON ds.t USING BTREE (id) WHERE id > 0",
        "ALTER TABLE ds.t ADD COLUMN IF NOT EXISTS c VARCHAR(40) NOT NULL DEFAULT '未知'",
        "ALTER TABLE ds.t ADD CONSTRAINT fk FOREIGN KEY (c) REFERENCES ds.target(id) NOT"
            + " DEFERRABLE",
        "ALTER TABLE ds.t ADD CONSTRAINT ck CHECK (id > 0) NOT VALID",
        "ALTER TABLE ds.t ALTER COLUMN c SET DATA TYPE BIGINT, ALTER COLUMN c SET NOT NULL",
        "ALTER TABLE ds.t ALTER COLUMN c TYPE VARCHAR(90)",
        "ALTER TABLE ds.t ALTER COLUMN c SET DEFAULT (1 + 2)",
        "ALTER TABLE ds.t ALTER COLUMN c DROP DEFAULT, ALTER COLUMN c DROP NOT NULL",
        "ALTER TABLE ds.t MODIFY COLUMN c DECIMAL(12,2)",
        "ALTER TABLE ds.t CHANGE COLUMN c new_c VARCHAR(10)",
        "ALTER TABLE ds.t RENAME COLUMN c TO new_c",
        "ALTER TABLE ds.t DROP COLUMN IF EXISTS c RESTRICT, DROP CONSTRAINT fk",
        "ALTER TABLE ds.t DROP FOREIGN KEY fk",
        "ALTER TABLE ds.t DROP PRIMARY KEY",
        "TRUNCATE TABLE ds.a, ds.b RESTART IDENTITY CASCADE",
        "TRUNCATE ds.a",
        "RENAME TABLE ds.a TO ds.b, ds.c TO ds.d",
        "DROP TABLE IF EXISTS ds.a, ds.b CASCADE",
        "DROP VIEW ds.v",
        "DROP INDEX ds.idx ON ds.t",
        "COMMENT ON TABLE ds.t IS NULL",
        "COMMENT ON INDEX ds.idx IS '説明'"
      })
  void admitsBoundedBasicNativeGrammar(String sql) {
    var plan = FederationDdlPlan.parse(sql);
    assertTrue(plan.references().hasTableReferences());
    assertEquals(sql, plan.nativeSql("unrelated_source"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "CREATE SCHEMA ds",
        "CREATE FUNCTION f() RETURNS INT AS 'x'",
        "CREATE TRIGGER tr ON ds.t",
        "CREATE TABLE ds.t (id INT); DROP TABLE secret",
        "CREATE TABLE ds.t (id INT) GIBBERISH",
        "CREATE TABLE ds.t (id custom_type)",
        "CREATE TABLE ds.t (id INT DEFAULT unknown())",
        "CREATE TABLE ds.t (id INT DEFAULT 'a\\b')",
        "CREATE TABLE ds.t (id INT,)",
        "CREATE TABLE ds.t (id INT) INHERITS (secret)",
        "CREATE TABLE ds.t PARTITION OF secret",
        "CREATE TABLE ds.t (id INT) /*!50000 AS SELECT * FROM secret */",
        "ALTER TABLE ds.t ENABLE TRIGGER ALL",
        "ALTER TABLE ds.t ATTACH PARTITION secret",
        "ALTER TABLE ds.t ADD CONSTRAINT fk FOREIGN KEY (id) REFERENCES ds.parent(id) ON DELETE"
            + " MAGIC",
        "ALTER TABLE ds.t RENAME TO",
        "ALTER TABLE ds.t SET SCHEMA secret",
        "DROP TABLE ds.t trailing",
        "DROP TABLE ds.t,",
        "TRUNCATE ds.t; DELETE FROM secret",
        "CREATE INDEX idx ON ds.t",
        "COMMENT ON COLUMN t IS 'bad'",
        "CREATE VIEW ds.v AS SELECT * FROM TABLE(f())",
        "CREATE TABLE ds.t AS SELECT * FROM",
        "CREATE TABLE ds.t AS DELETE FROM secret",
        "CREATE TABLE ds.t (id INT CHECK (id >))"
      })
  void unknownOrIncompleteGrammarCannotFallThroughToNativeExecution(String sql) {
    var failure =
        assertThrows(FederationSqlWriteException.class, () -> FederationDdlPlan.parse(sql));
    assertEquals(FederationSqlWriteException.Code.SQL_WRITE_UNSUPPORTED, failure.errorCode());
  }

  @Test
  void rejectsExcessiveObjectsBeforeNativeRewriting() {
    var names = java.util.stream.IntStream.range(0, 257).mapToObj(i -> "ds.t" + i).toList();
    assertThrows(
        FederationSqlWriteException.class,
        () -> FederationDdlPlan.parse("DROP TABLE " + String.join(",", names)));
  }
}
