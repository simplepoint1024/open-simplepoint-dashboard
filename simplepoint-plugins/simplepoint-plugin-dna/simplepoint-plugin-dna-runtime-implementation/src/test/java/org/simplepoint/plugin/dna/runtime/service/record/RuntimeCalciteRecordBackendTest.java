package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;

class RuntimeCalciteRecordBackendTest {

  @Test
  void commitsCommandGroupThroughCalciteAndReadsItBack() throws Exception {
    Fixture fixture = fixture();
    RecordBatchExecutionResult result = fixture.backend().execute(
        fixture.model(), new RecordWriteAdmission("tenant-a", 0),
        List.of(
            insert("first", "tenant-a"),
            insert("second", "tenant-a")
        ),
        10_000
    );

    assertThat(result.affectedRows()).containsExactly(1, 1);
    assertThat(result.pushedStatementCount()).isGreaterThan(0);
    var query = fixture.backend().query(
        fixture.model(),
        new RecordSelectPlan(
            "SELECT \"id\", \"record_version\", \"title\" FROM \"runtime\".\"records\""
                + " WHERE \"tenant_id\" = ? ORDER BY \"id\"",
            List.of("tenant-a"),
            List.of("title"),
            10
        ),
        10_000
    );
    assertThat(query.rows()).hasSize(2);
  }

  @Test
  void rollsBackEveryStatementWhenOneMutationMisses() throws Exception {
    Fixture fixture = fixture();

    assertThatThrownBy(() -> fixture.backend().execute(
        fixture.model(), new RecordWriteAdmission("tenant-a", 0),
        List.of(
            insert("will-rollback", "tenant-a"),
            new RecordSqlStatement(
                "UPDATE \"runtime\".\"records\" SET \"title\" = ? WHERE \"id\" = ?",
                List.of("changed", "missing"),
                RecordMutationType.UPDATE,
                "missing",
                1,
                Instant.now()
            )
        ),
        10_000
    )).isInstanceOf(RecordMutationMissException.class);

    try (Connection connection = fixture.dataSource().getConnection();
         Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM records")) {
      result.next();
      assertThat(result.getLong(1)).isZero();
    }
  }

  @Test
  void inspectionRequiresDatabaseAggregationAndAlwaysRestoresTheOrdinaryPlanner() throws Exception {
    Fixture fixture = fixture();
    fixture.backend().execute(fixture.model(), new RecordWriteAdmission("tenant-a", 0), List.of(insert("first", "tenant-a")), 10_000);
    var count = new RecordSelectPlan("SELECT COUNT(*) FROM \"runtime\".\"records\"", List.of(), List.of(), 1);
    var result = fixture.backend().aggregateQuery(fixture.model(), count, 10_000);
    assertThat(result.rows().getFirst().getFirst()).isEqualTo(1L);
    assertThat(result.analysis().pushedDownOperators()).contains("Aggregate");

    // COLLECT has a local implementation but is not supported by the JDBC dialect. Ordinary
    // queries can use it; a management inspection must fail rather than transfer record values.
    var local = new RecordSelectPlan("SELECT COLLECT(\"title\") FROM \"runtime\".\"records\"",
        List.of(), List.of(), 1);
    assertThat(fixture.backend().query(fixture.model(), local, 10_000).rows()).hasSize(1);
    assertThatThrownBy(() -> fixture.backend().aggregateQuery(fixture.model(), local, 10_000))
        .isInstanceOf(DnaRuntimeException.class);
    assertThat(fixture.backend().query(fixture.model(), local, 10_000).rows()).hasSize(1);
  }

  private static Fixture fixture() throws Exception {
    JdbcDataSource h2 = new JdbcDataSource();
    h2.setURL("jdbc:h2:mem:record-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    h2.setUser("sa");
    SimpleDataSource dataSource = new SimpleDataSource(h2);
    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE records ("
          + "id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, "
          + "record_version BIGINT NOT NULL, title VARCHAR(128) NOT NULL)"
      );
    }
    ManagedRuntimeDataSourceProvider provider = mock(ManagedRuntimeDataSourceProvider.class);
    ManagedRuntimeDataSource handle = new ManagedRuntimeDataSource(
        dataSource,
        false,
        null,
        null,
        H2RecordWriteFenceFixture.prepare(dataSource, "tenant-a", "model-1"),
        null,
        null,
        "PUBLIC"
    );
    RuntimeRecordModel model = new RuntimeRecordModel(
        "model-1",
        "app-1",
        "form-1",
        1,
        new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(), List.of()),
        new PhysicalSchemaTarget(
            "model-1",
            new RuntimeMetadataTarget("source-1", null, "PUBLIC", "records")
        )
    );
    when(provider.open(model.target())).thenReturn(handle);
    return new Fixture(
        new RuntimeCalciteRecordBackend(provider, new DefaultCalciteQueryEngine()),
        model,
        dataSource
    );
  }

  private static RecordSqlStatement insert(final String id, final String tenant) {
    return new RecordSqlStatement(
        "INSERT INTO \"runtime\".\"records\" "
            + "(\"id\", \"tenant_id\", \"record_version\", \"title\") VALUES (?, ?, ?, ?)",
        List.of(id, tenant, 0L, "title-" + id),
        RecordMutationType.CREATE,
        id,
        0,
        Instant.now()
    );
  }

  private record Fixture(
      RuntimeCalciteRecordBackend backend,
      RuntimeRecordModel model,
      SimpleDataSource dataSource
  ) {
  }
}
