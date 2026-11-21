package org.simplepoint.plugin.dna.federation.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException;

class FederationDmlPlanTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "INSERT INTO ds.dest(id) VALUES(1)",
        "INSERT INTO ds.dest VALUES(1,DEFAULT)",
        "INSERT INTO ds.dest(id) SELECT id FROM ds.source"
      })
  void defaultsUseTheAdmittedNativeLaneBeforeRelationalPlanning(String sql) {
    assertThat(FederationDmlPlan.parse(sql).calcite()).isFalse();
  }

  @Test
  void nativeDirectoryAndIdentifierRenderingPreservesLiteralValues() {
    var plan = FederationDmlPlan.parse("INSERT INTO \"交易\"(\"名字\") VALUES('中文;DEFAULT')");
    var qualified = FederationDmlPlan.parse(plan.qualifiedSql("\"ds\".\"业务\""));
    assertThat(qualified.nativeSql("ds", "`"))
        .isEqualTo("INSERT INTO `业务`.`交易`(`名字`) VALUES('中文;DEFAULT')");
    assertThat(qualified.nativeSql("ds", "\""))
        .isEqualTo("INSERT INTO \"业务\".\"交易\"(\"名字\") VALUES('中文;DEFAULT')");
  }

  @Test
  void nestedDefaultDirectoryQualifiesPhysicalNamesWithoutCapturingCteOrValues() {
    var plan =
        FederationDmlPlan.parse(
            "INSERT INTO destination WITH picked AS "
                + "(SELECT id, 'source' AS label FROM source) SELECT id FROM picked");
    assertThat(plan.qualifiedSql("\"ds\".\"业务.目录\""))
        .contains(
            "INSERT INTO \"ds\".\"业务.目录\".destination",
            "FROM \"ds\".\"业务.目录\".source",
            "'source' AS label",
            "FROM picked")
        .doesNotContain("\"业务.目录\".\"picked\"");
  }

  @ParameterizedTest
  @ValueSource(strings = {"ds.*", "ds; DELETE FROM source", "ds + source", "a.b.c.d"})
  void defaultDirectoryRequiresBoundedIdentifierPath(String directory) {
    var plan = FederationDmlPlan.parse("INSERT INTO destination VALUES (1)");
    assertThatThrownBy(() -> plan.qualifiedSql(directory))
        .isInstanceOf(FederationSqlWriteException.class);
  }

  @Test
  void physicalFootprintKeepsOriginalUnicodeQuotesAndSeparateAccessRoles() {
    var sql = "INSERT INTO ds.straße (id) SELECT id FROM \"ds\".\"顧客\"\"表\"";
    var plan = FederationDmlPlan.parse(sql);
    assertThat(plan.objects()).hasSize(2);
    var target = plan.objects().getFirst();
    assertThat(target.role()).isEqualTo(FederationDmlPlan.Role.TARGET);
    assertThat(target.names()).containsExactly("ds", "straße");
    assertThat(target.quoted()).containsExactly(false, false);
    assertThat(sql.substring(target.start(), target.prefixEnd())).isEqualTo("ds.");
    var read = plan.objects().getLast();
    assertThat(read.role()).isEqualTo(FederationDmlPlan.Role.READ);
    assertThat(read.names()).containsExactly("ds", "顧客\"表");
    assertThat(read.quoted()).containsExactly(true, true);
    assertThat(plan.nativeSql("ds"))
        .isEqualTo("INSERT INTO straße (id) SELECT id FROM \"顧客\"\"表\"");
  }

  @Test
  void readingAndWritingTheSameTableRetainsBothOccurrences() {
    var plan =
        FederationDmlPlan.parse(
            "UPDATE ds.accounts SET total = (SELECT MAX(total) FROM ds.accounts)");
    assertThat(plan.objects().stream().map(FederationDmlPlan.ObjectReference::role))
        .containsExactly(FederationDmlPlan.Role.TARGET, FederationDmlPlan.Role.READ);
    assertThat(plan.objects().getFirst().start()).isLessThan(plan.objects().getLast().start());
  }

  @Test
  void mergeSyntheticCallsDoNotCreateAdditionalTargetsOrTurnSourcesIntoWrites() {
    var plan =
        FederationDmlPlan.parse(
            "MERGE INTO ds.dest AS d USING ds.source AS s ON d.id=s.id "
                + "WHEN MATCHED THEN UPDATE SET amount=s.amount "
                + "WHEN NOT MATCHED THEN INSERT (id,amount) VALUES (s.id,s.amount)");
    assertThat(
            plan.objects().stream()
                .filter(reference -> reference.role() == FederationDmlPlan.Role.TARGET))
        .singleElement()
        .satisfies(reference -> assertThat(reference.names()).containsExactly("ds", "dest"));
    assertThat(
            plan.objects().stream()
                .filter(reference -> reference.role() == FederationDmlPlan.Role.READ))
        .singleElement()
        .satisfies(reference -> assertThat(reference.names()).containsExactly("ds", "source"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "INSERT INTO ds.dest WITH straße AS (SELECT id FROM ds.input) SELECT id FROM strasse",
        "INSERT INTO ds.dest WITH source AS (SELECT id FROM ds.input) SELECT id FROM \"SOURCE\"",
        "INSERT INTO ds.dest WITH \"SOURCE\" AS (SELECT id FROM ds.input) SELECT id FROM source"
      })
  void nativeAndCalciteCteAmbiguityCannotHidePhysicalRead(String sql) {
    assertThatThrownBy(() -> FederationDmlPlan.parse(sql))
        .isInstanceOf(FederationSqlWriteException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "WITH source AS (SELECT id FROM ds.input) SELECT id FROM SOURCE",
        "WITH \"Source\" AS (SELECT id FROM ds.input) SELECT id FROM \"Source\"",
        "WITH straße AS (SELECT id FROM ds.input) SELECT id FROM straße",
        "WITH source AS (SELECT id FROM ds.input) SELECT id FROM (WITH source AS "
            + "(SELECT id FROM ds.other) SELECT id FROM source) AS nested"
      })
  void unambiguousCteBindingsRemainAvailable(String query) {
    var plan = FederationDmlPlan.parse("INSERT INTO ds.dest " + query);
    assertThat(
            plan.objects().stream()
                .filter(reference -> reference.role() == FederationDmlPlan.Role.READ)
                .map(FederationDmlPlan.ObjectReference::names))
        .allSatisfy(names -> assertThat(names).hasSize(2).startsWith("ds"));
  }

  @Test
  void explicitTableQueriesIncludeTheirPhysicalInput() {
    var plan = FederationDmlPlan.parse("INSERT INTO ds.dest TABLE other.source");
    assertThat(plan.references().identifiers())
        .containsExactly(List.of("DS", "DEST"), List.of("OTHER", "SOURCE"));
    assertThat(plan.nativeSql("other")).isEqualTo("INSERT INTO ds.dest TABLE source");
  }

  @Test
  void collectsTablesInsideValuesAndPredicates() {
    var plan =
        FederationDmlPlan.parse(
            "UPDATE ds.orders SET amount = "
                + "(SELECT MAX(amount) FROM ds.prices) WHERE EXISTS "
                + "(SELECT 1 FROM other.customers WHERE id = orders.id)");
    assertThat(plan.references().identifiers())
        .containsExactly(
            List.of("DS", "ORDERS"), List.of("DS", "PRICES"), List.of("OTHER", "CUSTOMERS"));
    assertThat(plan.calcite()).isTrue();
  }

  @Test
  void mergeSourceAndNestedReadAreIncludedAndRewritten() {
    String sql =
        "MERGE INTO ds.orders AS t USING ds.incoming AS s ON t.id = s.id "
            + "WHEN MATCHED THEN UPDATE SET amount = (SELECT MAX(amount) FROM ds.prices) "
            + "WHEN NOT MATCHED THEN INSERT (id, amount) VALUES (s.id, s.amount)";
    var plan = FederationDmlPlan.parse(sql);
    assertThat(plan.references().identifiers())
        .containsExactly(
            List.of("DS", "ORDERS"), List.of("DS", "INCOMING"), List.of("DS", "PRICES"));
    assertThat(plan.calcite()).isFalse();
    assertThat(plan.nativeSql("ds")).isEqualTo(sql.replace("ds.", ""));
  }

  @Test
  void nonRecursiveCteDoesNotHideItsPhysicalInputOrLeakIntoSiblingScopes() {
    var plan =
        FederationDmlPlan.parse(
            "INSERT INTO ds.dest "
                + "WITH src AS (SELECT id FROM src), filtered AS (SELECT id FROM src) "
                + "SELECT id FROM filtered UNION ALL SELECT id FROM ds.src");
    assertThat(plan.references().identifiers())
        .containsExactly(List.of("DS", "DEST"), List.of("SRC"), List.of("DS", "SRC"));
  }

  @Test
  void innerCteDoesNotHideOuterPhysicalTable() {
    var plan =
        FederationDmlPlan.parse(
            "INSERT INTO ds.dest SELECT a.id FROM "
                + "(WITH src AS (SELECT id FROM ds.input) SELECT id FROM src) AS a "
                + "JOIN src AS b ON a.id = b.id");
    assertThat(plan.references().identifiers())
        .containsExactly(List.of("DS", "DEST"), List.of("DS", "INPUT"), List.of("SRC"));
  }

  @Test
  void nativeRewritingPreservesCaseUnicodeLiteralsAndLinePositions() {
    String sql =
        "-- heading\r\n\tINSERT INTO \"ds\" . \"Case.Schema\".\"交易\" (\"名字\")\r\n"
            + "SELECT 'ds.日本語; -- unchanged' FROM\t\"ds\".\"MixedCase\"; -- ds.comment";
    var plan = FederationDmlPlan.parse(sql);
    assertThat(plan.nativeSql("ds"))
        .isEqualTo(
            "-- heading\r\n\tINSERT INTO \"Case.Schema\".\"交易\" (\"名字\")\r\n"
                + "SELECT 'ds.日本語; -- unchanged' FROM\t\"MixedCase\"; -- ds.comment");
  }

  @Test
  void nativeRewritingDoesNotRemoveAnUnrelatedSchemaOrAlias() {
    String sql = "INSERT INTO other.dest SELECT ds.id FROM other.src AS ds";
    assertThat(FederationDmlPlan.parse(sql).nativeSql("ds")).isEqualTo(sql);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "DELETE FROM ds.orders; DELETE FROM ds.customers",
        "DELETE FROM ds.orders;;",
        "DELETE FROM ds.orders --ambiguous comment",
        "DELETE FROM ds.orders WHERE (",
        "UPDATE ds.orders SET value = 'unterminated",
        "UPDATE ds.orders SET value = 'a\\b'",
        "/*!50000 DELETE FROM secret */ DELETE FROM ds.orders",
        "/*M! DELETE FROM secret */ DELETE FROM ds.orders",
        "/* outer /* inner */ end */ DELETE FROM ds.orders",
        "INSERT INTO ds.orders SELECT * FROM TABLE(dynamic_tables())",
        "UPDATE ds.orders GIBBERISH",
        "SELECT * FROM ds.orders",
        "-- only comment"
      })
  void rejectsIncompleteOrAmbiguousAnalysisWithoutExposingSql(String sql) {
    assertThatThrownBy(() -> FederationDmlPlan.parse(sql))
        .isInstanceOf(FederationSqlWriteException.class)
        .hasMessage("SQL write cannot be completely analyzed by the supported grammar")
        .hasNoCause();
  }

  @Test
  void depthAndLengthAreBoundedBeforeParsing() {
    assertThatThrownBy(
            () ->
                FederationDmlPlan.parse(
                    "DELETE FROM ds.orders WHERE " + "(".repeat(65) + "true" + ")".repeat(65)))
        .isInstanceOf(FederationSqlWriteException.class);
    assertThatThrownBy(() -> FederationDmlPlan.parse("DELETE FROM ds.orders " + " ".repeat(12000)))
        .isInstanceOf(FederationSqlWriteException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "DELETE FROM ds.orders; -- trailing comment",
        "DELETE FROM ds.orders /* before */; /* after */",
        "INSERT INTO ds.orders VALUES (';/*not comment*/''value')"
      })
  void acceptsOneStatementWithStandardQuotesAndTrailingComments(String sql) {
    assertThat(FederationDmlPlan.parse(sql).objects()).isNotEmpty();
  }

  @Test
  void framingUnderstandsNativeIdentifierQuotesWithoutTreatingContentsAsSql() {
    FederationSqlStatementBoundary.requireSingle("ALTER TABLE `a;``b` ADD c int;");
    FederationSqlStatementBoundary.requireSingle("ALTER TABLE [a;]]b] ADD c int;");
    assertThatThrownBy(
            () ->
                FederationSqlStatementBoundary.requireSingle(
                    "ALTER TABLE `a;``b` ADD c int; DROP TABLE secret"))
        .isInstanceOf(FederationSqlWriteException.class);
  }
}
