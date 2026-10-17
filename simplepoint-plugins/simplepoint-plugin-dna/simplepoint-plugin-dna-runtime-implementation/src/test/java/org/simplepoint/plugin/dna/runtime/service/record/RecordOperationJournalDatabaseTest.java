package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.datascopeannotation.DataScopeCondition;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.dialect.MysqlJdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.dialect.PostgresqlJdbcDatabaseDialect;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.dialect.DatabaseIdentity;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.configuration.DnaRuntimeExecutionProperties;
import org.simplepoint.plugin.dna.runtime.service.schema.JdbcPhysicalSchemaBackend;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource;
import org.simplepoint.plugin.dna.runtime.service.schema.MysqlRuntimeSchemaDialect;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaSnapshotCodec;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.schema.PostgresqlRuntimeSchemaDialect;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordJournalProvisioner;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordWriteFenceProvisioner;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordWriteFenceSql;
import org.simplepoint.plugin.dna.runtime.service.schema.RuntimeSchemaDialect;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real Calcite/JDBC tests for atomic receipts, acknowledgement loss and concurrent replay. */
class RecordOperationJournalDatabaseTest {
  private static final RuntimeActor ACTOR = new RuntimeActor("tenant-a", "user-a");
  private static final RecordSchemaReference SCHEMA = new RecordSchemaReference(1, "a".repeat(64));

  @Test
  void postgresqlReceiptsCommitAtomicallyAndSurviveLaterMutations() throws Exception {
    verify("POSTGRES", true);
  }

  @Test
  void mysqlReceiptsCommitAtomicallyAndSurviveLaterMutations() throws Exception {
    verify("MYSQL", false);
  }

  private void verify(String database, boolean postgres) throws Exception {
    String prefix = "DNA_RUNTIME_TEST_" + database;
    String url = System.getenv(prefix + "_URL");
    assumeTrue(url != null && !url.isBlank(), "Real target database is required");
    var source = new DriverManagerDataSource(url, System.getenv(prefix + "_USER"), System.getenv(prefix + "_PASSWORD"));
    String namespace = "journal_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
      statement.execute("CREATE " + (postgres ? "SCHEMA " : "DATABASE ") + namespace);
    }
    try {
      run(source, namespace, postgres);
    } finally {
      try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
        statement.execute("DROP " + (postgres ? "SCHEMA " : "DATABASE ") + namespace + (postgres ? " CASCADE" : ""));
      }
    }
  }

  private void run(DataSource source, String namespace, boolean postgres) throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var codec = new PhysicalSchemaSnapshotCodec(mapper);
    JdbcDatabaseDialect core = postgres ? new PostgresqlJdbcDatabaseDialect() : new MysqlJdbcDatabaseDialect();
    RuntimeSchemaDialect dialect = postgres ? new PostgresqlRuntimeSchemaDialect(codec) : new MysqlRuntimeSchemaDialect(codec);
    JdbcDatabaseDialect.SupportContext context;
    DatabaseIdentity database;
    String catalog;
    try (Connection connection = source.getConnection()) {
      var metadata = connection.getMetaData();
      catalog = postgres ? connection.getCatalog() : namespace;
      context = new JdbcDatabaseDialect.SupportContext(core.code(), metadata.getDriverName(), metadata.getDatabaseProductName(),
          metadata.getDatabaseProductVersion(), catalog, postgres ? namespace : null, metadata.getIdentifierQuoteString(), Map.of());
      database = new DatabaseIdentity(metadata.getDatabaseProductName(), metadata.getDatabaseProductVersion(),
          metadata.getDatabaseMajorVersion(), metadata.getDatabaseMinorVersion());
    }
    var failCommit = new AtomicBoolean();
    var failRollback = new AtomicBoolean();
    var opened = new AtomicInteger();
    var dataSource = new SimpleDataSource(faultSource(source, failCommit, failRollback, opened));
    var provider = mock(ManagedRuntimeDataSourceProvider.class);
    when(provider.open(any())).thenReturn(new ManagedRuntimeDataSource(dataSource, false, context, core, dialect,
        database, catalog, postgres ? namespace : null));
    var schema = new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(new LogicalFieldDefinition("title", "title", "Title",
        LogicalFieldType.STRING, false, 128, null, null, null)), List.of());
    var target = new PhysicalSchemaTarget("model-a", new RuntimeMetadataTarget("source-a", catalog,
        postgres ? namespace : null, "records"));
    var ddl = new JdbcPhysicalSchemaBackend(provider, codec);
    var journal = new RecordJournalProvisioner(ddl);
    journal.prepare(target);
    journal.prepare(target);
    var plan = ddl.planInitial(target, "Records", schema);
    for (var step : plan.steps()) {
      ddl.execute(target, plan.dialectId(), step);
    }
    var model = new RuntimeRecordModel("model-a", "app-a", "form-a", 1, schema, target);
    var backend = new RuntimeCalciteRecordBackend(provider, new DefaultCalciteQueryEngine());
    new RecordWriteFencePreparation(new RecordWriteFenceProvisioner(ddl), backend).prepare(ACTOR, target);
    var compiler = new RecordSqlCompiler(new StandardLogicalValueConverter());
    var access = new RecordAccessContext(ACTOR, "app.records", Set.of("title"), Set.of("title"), Set.of(),
        new DataScopeCondition("ALL", "created_org_dept_id", "created_by", "user-a", Set.of()), null);
    var create = compiler.mutation(model, access, new RecordCreateMutation("create", Map.of("title", "initial")), Instant.now());
    assertThatThrownBy(() -> backend.execute(model, new RecordWriteAdmission("unprepared-tenant", 0), List.of(create), 15_000))
        .isInstanceOfSatisfying(DnaRuntimeException.class, failure -> assertThat(failure.errorCode())
            .isEqualTo(DnaRuntimeErrorCode.PERSISTENCE_FAILED));
    assertThat(backend.query(model, new RecordSelectPlan("SELECT \"id\" FROM \"runtime\".\"records\"",
        List.of(), List.of(), 1), 15_000).rows()).isEmpty();
    backend.execute(model, new RecordWriteAdmission("tenant-a", 0), List.of(create), 15_000);
    String id = create.recordId();
    var firstMutation = new RecordUpdateMutation("command", id, 0, Map.of("title", "first"));
    final var first = compiler.mutation(model, access, firstMutation, Instant.parse("2026-10-13T01:02:03Z"));
    var request = new RecordTransactionCommand("model-a", SCHEMA, List.of(firstMutation), 15_000, "first");
    var fingerprint = new RecordRequestFingerprint(mapper);
    final var identity = RecordOperationIdentity.of(ACTOR, request, fingerprint.of(request));
    var requestContext = mock(RuntimeRequestContext.class);
    var resolver = mock(RuntimeRecordModelResolver.class);
    var policy = mock(RuntimeRecordAccessPolicy.class);
    when(requestContext.requireActor()).thenReturn(ACTOR);
    when(resolver.resolve(ACTOR, "model-a", SCHEMA)).thenReturn(model);
    when(policy.authorize(ACTOR, model)).thenReturn(access);
    var transactions = new RecordTransactionServiceImpl(requestContext, resolver, policy, compiler, backend,
        mock(RecordAuditSupport.class), new RecordAdmissionController(new DnaRuntimeExecutionProperties()),
        mock(RecordExecutionObserver.class), fingerprint);
    var commands = new RecordCommandServiceImpl(mock(RecordCreateCoordinator.class), transactions);
    var keyedUpdate = new RecordUpdateCommand("model-a", SCHEMA, id, 0, Map.of("title", "first"), "first");
    assertThat(commands.findUpdateResult(keyedUpdate)).isNull();


    failCommit.set(true);
    var recovered = backend.execute(model, new RecordWriteAdmission("tenant-a", 0), List.of(first), 15_000, identity);
    assertThat(recovered.replayedResults()).hasSize(1);
    assertThat(recovered.replayedResults().getFirst().version()).isEqualTo(1);
    assertThat(recovered.replayedResults().getFirst().updatedAt()).isEqualTo(first.updatedAt());

    var later = compiler.mutation(model, access, new RecordUpdateMutation("later", id, 1, Map.of("title", "later")), Instant.now());
    backend.execute(model, new RecordWriteAdmission("tenant-a", 0), List.of(later), 15_000);
    var replay = backend.execute(model, new RecordWriteAdmission("tenant-a", 0), List.of(first), 15_000, identity);
    assertThat(replay.replayedResults()).isEqualTo(recovered.replayedResults());
    assertVersion(backend, model, id, 2);
    assertThat(commands.findUpdateResult(keyedUpdate)).isEqualTo(recovered.replayedResults().getFirst());
    assertThat(commands.update(keyedUpdate)).isEqualTo(recovered.replayedResults().getFirst());
    assertVersion(backend, model, id, 2);
    var conflict = new RecordOperationIdentity(identity.id(), identity.tenantId(), identity.actorId(),
        identity.modelId(), identity.key(), "b".repeat(64));
    assertThatThrownBy(() -> backend.execute(model, new RecordWriteAdmission("tenant-a", 0), List.of(first), 15_000, conflict))
        .isInstanceOfSatisfying(DnaRuntimeException.class, failure -> assertThat(failure.errorCode())
            .isEqualTo(DnaRuntimeErrorCode.RECORD_IDEMPOTENCY_CONFLICT));

    // A failed group leaves neither its earlier mutation nor its receipt committed.
    var rollbackIdentity = RecordOperationIdentity.of(ACTOR,
        new RecordTransactionCommand("model-a", SCHEMA, List.of(firstMutation), 15_000, "rollback"), "c".repeat(64));
    var change = compiler.mutation(model, access, new RecordUpdateMutation("change", id, 2, Map.of("title", "group")), Instant.now());
    var missing = compiler.mutation(model, access, new RecordDeleteMutation("missing", UUID.randomUUID().toString(), 0), Instant.now());
    failRollback.set(true);
    assertThatThrownBy(() -> backend.execute(model, new RecordWriteAdmission("tenant-a", 0), List.of(change, missing), 15_000, rollbackIdentity))
        .isInstanceOf(RecordMutationMissException.class);
    assertVersion(backend, model, id, 2);
    assertThat(new RecordOperationJournal().read(rollbackIdentity, backend.query(model,
        new RecordOperationJournal().lookup(rollbackIdentity), 15_000))).isNull();

    var concurrentIdentity = RecordOperationIdentity.of(ACTOR,
        new RecordTransactionCommand("model-a", SCHEMA, List.of(firstMutation), 15_000, "concurrent"), "d".repeat(64));
    var gate = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var one = pool.submit(() -> {
        gate.await();
        return backend.execute(model, new RecordWriteAdmission("tenant-a", 0), List.of(change), 15_000, concurrentIdentity);
      });
      var two = pool.submit(() -> {
        gate.await();
        return backend.execute(model, new RecordWriteAdmission("tenant-a", 0), List.of(change), 15_000, concurrentIdentity);
      });
      gate.countDown();
      assertThat(one.get(30, TimeUnit.SECONDS).affectedRows()).containsExactly(1);
      assertThat(two.get(30, TimeUnit.SECONDS).affectedRows()).containsExactly(1);
    }
    assertVersion(backend, model, id, 3);
    var deleted = compiler.mutation(model, access, new RecordDeleteMutation("delete", id, 3), Instant.now());
    var deleteIdentity = RecordOperationIdentity.of(ACTOR,
        new RecordTransactionCommand("model-a", SCHEMA, List.of(new RecordDeleteMutation("delete", id, 3)), 15_000, "delete"), "e".repeat(64));
    backend.execute(model, new RecordWriteAdmission("tenant-a", 0), List.of(deleted), 15_000, deleteIdentity);
    assertThat(commands.findUpdateResult(keyedUpdate)).isEqualTo(recovered.replayedResults().getFirst());
    new AdmissionScenario(source, model, backend, transactions,
        new RecordWriteFencePreparation(new RecordWriteFenceProvisioner(ddl), backend),
        dialect.recordWriteFenceSql(core, context, target)).verify();
    RecordWritePolicyDatabaseAssertions.verify(source, model, backend, compiler, access,
        dialect.recordWriteFenceSql(core, context, target), failCommit, opened);
    assertThat(commands.update(keyedUpdate)).isEqualTo(recovered.replayedResults().getFirst());
    assertThat(commands.update(new RecordUpdateCommand("model-a", SCHEMA, id, 0,
        Map.of("title", "first"), "first", 8))).isEqualTo(recovered.replayedResults().getFirst());
    when(policy.authorize(ACTOR, model)).thenThrow(new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_ACCESS_DENIED, "Resource was revoked"));
    assertThatThrownBy(() -> commands.findUpdateResult(keyedUpdate))
        .isInstanceOfSatisfying(DnaRuntimeException.class, failure -> assertThat(failure.errorCode())
            .isEqualTo(DnaRuntimeErrorCode.RECORD_ACCESS_DENIED));
    assertThat(backend.execute(model, new RecordWriteAdmission("tenant-a", 0), List.of(deleted), 15_000, deleteIdentity)
        .replayedResults().getFirst().version()).isEqualTo(4);
    assertThat(backend.execute(model, new RecordWriteAdmission("tenant-a", 0), List.of(first), 15_000, identity)
        .replayedResults()).isEqualTo(recovered.replayedResults());
  }

  /** Uses production command/coordinator/transaction paths against the real target database. */
  private record AdmissionScenario(DataSource source, RuntimeRecordModel model, RuntimeCalciteRecordBackend backend,
      RecordTransactionServiceImpl transactions, RecordWriteFencePreparation preparation, RecordWriteFenceSql sql) {

    private void verify() throws Exception {
      advance(0, 7);
      preparation.prepare(ACTOR, model.target()); // Replayed Schema preparation must not reset admission.
      var receipts = mock(RecordCreateReceiptStore.class);
      var committed = new AtomicReference<RecordCommandResult>();
      String recordId = RecordRequestFingerprint.deterministicRecordId("tenant-a", "model-a", "admission-create");
      Instant operationAt = Instant.parse("2026-10-14T01:00:00Z");
      when(receipts.claim(any(), any(), any())).thenAnswer(ignored ->
          new RecordCreateReceiptClaim(recordId, operationAt, committed.get()));
      when(receipts.find(any(), any(), any())).thenAnswer(ignored ->
          new RecordCreateReceiptClaim(recordId, operationAt, committed.get()));
      when(receipts.complete(any(), any(), any(), any())).thenAnswer(invocation -> {
        committed.compareAndSet(null, invocation.getArgument(3));
        return committed.get();
      });
      var commands = new RecordCommandServiceImpl(new RecordCreateCoordinator(transactions, receipts,
          new RecordRequestFingerprint(new ObjectMapper().findAndRegisterModules())), transactions);
      var oldCreate = new RecordCreateCommand("model-a", SCHEMA, "admission-create", Map.of("title", "admitted"));
      reject(() -> commands.create(oldCreate));
      assertThat(commands.findCreateResult(oldCreate)).isNull();
      assertThat(backend.query(model, new RecordSelectPlan(
          "SELECT \"id\" FROM \"runtime\".\"records\" WHERE \"id\" = ?", List.of(recordId), List.of(), 1), 15_000).rows()).isEmpty();
      var created = commands.create(new RecordCreateCommand("model-a", SCHEMA, "admission-create", oldCreate.values(), 7));
      assertThat(created.recordId()).isEqualTo(recordId);

      var staleUpdate = new RecordUpdateCommand("model-a", SCHEMA, recordId, 0, Map.of("title", "changed"), "admission-update");
      reject(() -> commands.update(staleUpdate));
      assertThat(commands.findUpdateResult(staleUpdate)).isNull();
      var updated = commands.update(new RecordUpdateCommand("model-a", SCHEMA, recordId, 0,
          staleUpdate.values(), "admission-update", 7));
      assertThat(updated.version()).isEqualTo(1);

      var mutations = List.of(new RecordUpdateMutation("one", recordId, 1, Map.of("title", "group")));
      var staleGroup = new RecordTransactionCommand("model-a", SCHEMA, List.copyOf(mutations), 15_000, "admission-group");
      reject(() -> transactions.execute(staleGroup));
      assertThat(transactions.findResult(staleGroup)).isNull();
      reject(() -> transactions.execute(new RecordTransactionCommand("model-a", SCHEMA, List.copyOf(mutations), 15_000)));
      var group = transactions.execute(new RecordTransactionCommand("model-a", SCHEMA,
          List.copyOf(mutations), 15_000, "admission-group", 7));
      assertThat(group.results().getFirst().result().version()).isEqualTo(2);

      var staleDelete = new RecordDeleteCommand("model-a", SCHEMA, recordId, 2, "admission-delete");
      reject(() -> commands.delete(staleDelete));
      var deleted = commands.delete(new RecordDeleteCommand("model-a", SCHEMA, recordId, 2, "admission-delete", 7));
      assertThat(deleted.version()).isEqualTo(3);
      advance(7, 8);
      // Admission changes never reinterpret a committed business operation, even after deletion.
      assertThat(commands.create(oldCreate)).isEqualTo(created);
      assertThat(commands.findCreateResult(oldCreate)).isEqualTo(created);
      assertThat(commands.update(staleUpdate)).isEqualTo(updated);
      assertThat(commands.delete(staleDelete)).isEqualTo(deleted);
      assertThat(transactions.execute(staleGroup).results()).isEqualTo(group.results());
    }

    private void advance(long previous, long next) throws Exception {
      try (var connection = source.getConnection()) {
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        connection.setAutoCommit(false);
        new RecordWriteFence(sql).publish(connection, new RecordWriteFenceKey("tenant-a", "model-a"),
            previous, next, "f".repeat(64), "publisher", 15_000, ignored -> {});
        connection.commit();
      }
    }

    private void reject(Runnable action) {
      assertThatThrownBy(action::run).isInstanceOfSatisfying(DnaRuntimeException.class,
          failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_WRITE_POLICY_CONFLICT));
    }
  }

  private void assertVersion(RuntimeCalciteRecordBackend backend, RuntimeRecordModel model, String id, long version) {
    var rows = backend.query(model, new RecordSelectPlan("SELECT \"record_version\" FROM \"runtime\".\"records\" WHERE \"id\" = ?",
        List.of(id), List.of(), 1), 15_000).rows();
    assertThat(((Number) rows.getFirst().getFirst()).longValue()).isEqualTo(version);
  }

  private static DataSource faultSource(DataSource source, AtomicBoolean failCommit, AtomicBoolean failRollback, AtomicInteger opened) {
    return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
        (proxy, method, arguments) -> {
          try {
            Object value = method.invoke(source, arguments);
            if (!(value instanceof Connection connection)) {
              return value;
            }
            opened.incrementAndGet();
            return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (connectionProxy, connectionMethod, parameters) -> {
                  try {
                    if (connectionMethod.getName().equals("rollback") && failRollback.compareAndSet(true, false)) {
                      throw new SQLException("Simulated rollback failure before the transaction has ended");
                    }
                    Object result = connectionMethod.invoke(connection, parameters);
                    if (connectionMethod.getName().equals("commit") && failCommit.compareAndSet(true, false)) {
                      throw new SQLException("Simulated lost acknowledgement after a successful commit");
                    }
                    return result;
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
          } catch (InvocationTargetException failure) {
            throw failure.getCause();
          }
        });
  }
}
