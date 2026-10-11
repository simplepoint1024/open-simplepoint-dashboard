package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.datascopeannotation.DataScopeCondition;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCountQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilter;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordLookupQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSort;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSortDirection;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.configuration.DnaRuntimeExecutionProperties;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.schema.RuntimeSchemaDialect;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter;

class RecordServicesDatabaseTest {

  @Test
  void completesCrudOptimisticConflictAndAtomicRollbackThroughCalcite() throws Exception {
    Fixture fixture = fixture();

    var created = fixture.commands().create(new RecordCreateCommand(
        "model-1",
        Map.of("title", "First", "amount", 10)
    ));
    assertThat(created.version()).isZero();
    assertThat(fixture.queries().get(new RecordLookupQuery(
        "model-1",
        created.recordId(),
        List.of("title", "amount")
    )).values()).containsEntry("title", "First").containsEntry("amount", 10L);

    var updated = fixture.commands().update(new RecordUpdateCommand(
        "model-1",
        created.recordId(),
        0,
        Map.of("title", "Updated")
    ));
    assertThat(updated.version()).isEqualTo(1);
    var page = fixture.queries().page(new RecordPageQuery(
        "model-1",
        0,
        20,
        List.of("title", "amount"),
        new RecordFilter(RecordFilterOperator.GREATER_THAN, "amount", List.of(5), List.of()),
        List.of(new RecordSort("title", RecordSortDirection.ASC))
    ));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.records()).extracting(record -> record.values().get("title"))
        .containsExactly("Updated");

    assertThatThrownBy(() -> fixture.commands().update(new RecordUpdateCommand(
        "model-1",
        created.recordId(),
        0,
        Map.of("title", "Stale overwrite")
    ))).isInstanceOfSatisfying(DnaRuntimeException.class, exception ->
        assertThat(exception.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_VERSION_CONFLICT));

    assertThatThrownBy(() -> fixture.transactions().execute(new RecordTransactionCommand(
        "model-1",
        List.of(
            new RecordCreateMutation("new", Map.of("title", "Rolled back", "amount", 20)),
            new RecordUpdateMutation("missing", UUID.randomUUID().toString(), 0,
                Map.of("title", "Never applied"))
        ),
        10_000
    ))).isInstanceOfSatisfying(DnaRuntimeException.class, exception ->
        assertThat(exception.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_NOT_FOUND));
    assertThat(fixture.queries().count(new RecordCountQuery("model-1", null))).isEqualTo(1);

    fixture.commands().delete(new RecordDeleteCommand("model-1", created.recordId(), 1));
    assertThat(fixture.queries().count(new RecordCountQuery("model-1", null))).isZero();
    assertThatThrownBy(() -> fixture.queries().get(new RecordLookupQuery(
        "model-1",
        created.recordId(),
        List.of("title")
    ))).isInstanceOfSatisfying(DnaRuntimeException.class, exception ->
        assertThat(exception.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_NOT_FOUND));
  }

  private static Fixture fixture() throws Exception {
    JdbcDataSource h2 = new JdbcDataSource();
    h2.setURL("jdbc:h2:mem:record-services-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    h2.setUser("sa");
    SimpleDataSource dataSource = new SimpleDataSource(h2);
    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE records ("
          + "id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, "
          + "record_version BIGINT NOT NULL, created_by VARCHAR(64) NOT NULL, "
          + "created_org_dept_id VARCHAR(64), created_at TIMESTAMP NOT NULL, "
          + "updated_by VARCHAR(64) NOT NULL, updated_at TIMESTAMP NOT NULL, "
          + "deleted_at TIMESTAMP, title VARCHAR(128) NOT NULL, amount BIGINT)"
      );
    }

    RuntimeRecordModel model = model();
    ManagedRuntimeDataSourceProvider provider = mock(ManagedRuntimeDataSourceProvider.class);
    when(provider.open(model.target())).thenReturn(new ManagedRuntimeDataSource(
        dataSource,
        false,
        null,
        null,
        mock(RuntimeSchemaDialect.class),
        null,
        null,
        "PUBLIC"
    ));
    RuntimeRequestContext requestContext = mock(RuntimeRequestContext.class);
    RuntimeRecordModelResolver modelResolver = mock(RuntimeRecordModelResolver.class);
    RuntimeRecordAccessPolicy accessPolicy = mock(RuntimeRecordAccessPolicy.class);
    RuntimeActor actor = new RuntimeActor("tenant-a", "user-a");
    RecordAccessContext access = access(actor);
    when(requestContext.requireActor()).thenReturn(actor);
    when(modelResolver.resolve(actor, "model-1")).thenReturn(model);
    when(accessPolicy.authorize(actor, model)).thenReturn(access);

    RecordSqlCompiler compiler = new RecordSqlCompiler(
        new StandardLogicalValueConverter(new ObjectMapper())
    );
    RuntimeCalciteRecordBackend backend = new RuntimeCalciteRecordBackend(
        provider,
        new DefaultCalciteQueryEngine()
    );
    RecordAuditSupport audit = mock(RecordAuditSupport.class);
    DnaRuntimeExecutionProperties properties = new DnaRuntimeExecutionProperties();
    RecordAdmissionController admission = new RecordAdmissionController(properties);
    RecordExecutionObserver observer = mock(RecordExecutionObserver.class);
    RecordTransactionServiceImpl transactions = new RecordTransactionServiceImpl(
        requestContext,
        modelResolver,
        accessPolicy,
        compiler,
        backend,
        audit,
        admission,
        observer
    );
    RecordQueryServiceImpl queries = new RecordQueryServiceImpl(
        requestContext,
        modelResolver,
        accessPolicy,
        compiler,
        backend,
        audit,
        admission,
        observer
    );
    return new Fixture(
        new RecordCommandServiceImpl(transactions),
        transactions,
        queries
    );
  }

  private static RuntimeRecordModel model() {
    return new RuntimeRecordModel(
        "model-1",
        "app-1",
        "form-1",
        1,
        new PhysicalSchemaDefinition(
            DnaRuntimeApiVersion.CURRENT,
            List.of(
                new LogicalFieldDefinition(
                    "field-title", "title", "Title", LogicalFieldType.STRING,
                    false, 128, null, null, null
                ),
                new LogicalFieldDefinition(
                    "field-amount", "amount", "Amount", LogicalFieldType.INT64,
                    true, null, null, null, null
                )
            ),
            List.of()
        ),
        new PhysicalSchemaTarget(
            "model-1",
            new RuntimeMetadataTarget("source-1", null, "PUBLIC", "records")
        )
    );
  }

  private static RecordAccessContext access(final RuntimeActor actor) {
    return new RecordAccessContext(
        actor,
        "app.forms.orders",
        Set.of("id", "record_version", "title", "amount"),
        Set.of("title", "amount"),
        Set.of(),
        new DataScopeCondition(
            "ALL",
            "created_org_dept_id",
            "created_by",
            actor.actorId(),
            Set.of()
        ),
        "dept-a"
    );
  }

  private record Fixture(
      RecordCommandServiceImpl commands,
      RecordTransactionServiceImpl transactions,
      RecordQueryServiceImpl queries
  ) {
  }
}
