package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.schema.JdbcPhysicalSchemaBackend;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource;
import org.simplepoint.plugin.dna.runtime.service.schema.MysqlRuntimeSchemaDialect;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaSnapshotCodec;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.schema.PostgresqlRuntimeSchemaDialect;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordWriteFenceProvisioner;
import org.simplepoint.plugin.dna.runtime.service.schema.RuntimeOwnershipTestFixture;
import org.simplepoint.plugin.dna.runtime.service.schema.RuntimeSchemaDialect;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Production Calcite writes, generated foreign keys and transaction guards on both real databases.
 */
class RecordReferenceIntegrityDatabaseTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void retainedSourcesBlockDeletionAndUnlinkReleasesActiveReferences(boolean postgres)
      throws Exception {
    try (var f = new Fixture(postgres)) {
      var customer = f.create(f.customer, Map.of());
      f.save(f.customer, customer);
      var trade = f.create(f.trade, Map.of("customer_id", customer.recordId()));
      f.save(f.trade, trade);
      conflict(() -> f.save(f.customer, f.delete(f.customer, customer.recordId(), 0)));
      assertThat(f.deleted(f.customer, customer.recordId())).isFalse();
      var clear = new java.util.HashMap<String, Object>();
      clear.put("customer_id", null);
      f.save(f.trade, f.update(f.trade, trade.recordId(), 0, clear));
      f.save(f.customer, f.delete(f.customer, customer.recordId(), 0));
      assertThat(f.deleted(f.customer, customer.recordId())).isTrue();

      var retained = f.create(f.customer, Map.of());
      f.save(f.customer, retained);
      var historical = f.create(f.trade, Map.of("customer_id", retained.recordId()));
      f.save(f.trade, historical);
      f.save(f.trade, f.delete(f.trade, historical.recordId(), 0));
      conflict(() -> f.save(f.customer, f.delete(f.customer, retained.recordId(), 0)));
      assertThat(f.deleted(f.customer, retained.recordId())).isFalse();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void deletedTargetsRejectCreateRetargetAndWholeBatch(boolean postgres) throws Exception {
    try (var f = new Fixture(postgres)) {
      var target = f.create(f.customer, Map.of());
      f.save(f.customer, target);
      f.save(f.customer, f.delete(f.customer, target.recordId(), 0));
      var valid = f.create(f.trade, Map.of());
      var invalid = f.create(f.trade, Map.of("customer_id", target.recordId()));
      conflict(() -> f.save(f.trade, valid, invalid));
      assertThat(f.count(f.trade)).isZero();
      f.save(f.trade, valid);
      conflict(
          () ->
              f.save(
                  f.trade,
                  f.update(
                      f.trade, valid.recordId(), 0, Map.of("customer_id", target.recordId()))));
      assertThat(f.value(f.trade, valid.recordId(), "customer_id")).isNull();
      assertThat(((Number) f.value(f.trade, valid.recordId(), "record_version")).longValue())
          .isZero();
      // Tenant-qualified FK remains the final boundary, independently of application projections.
      var other =
          f.compiler.mutation(
              f.customer,
              access("tenant-b"),
              new RecordCreateMutation("other", Map.of()),
              Instant.now());
      f.backend.execute(
          f.customer, new RecordWriteAdmission("tenant-b", 0), List.of(other), 30_000);
      var crossTenant = f.create(f.trade, Map.of("customer_id", other.recordId()));
      assertThatThrownBy(() -> f.save(f.trade, crossTenant))
          .isInstanceOf(DnaRuntimeException.class);
      assertThat(f.count(f.trade)).isEqualTo(1);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void sourceCommitWinsAndTargetDeleteRollsBack(boolean postgres) throws Exception {
    try (var f = new Fixture(postgres);
        var workers = Executors.newFixedThreadPool(2)) {
      var customer = f.create(f.customer, Map.of());
      f.save(f.customer, customer);
      var trade = f.create(f.trade, Map.of("customer_id", customer.recordId()));
      var locked = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var writer =
          workers.submit(
              () ->
                  f.heldPublicationWrite(
                      f.trade,
                      trade,
                      locked,
                      release,
                      new RecordReferenceCheck(
                          "trade-customer",
                          f.trade.physicalModelId(),
                          trade.recordId(),
                          customer.recordId(),
                          false)));
      try {
        boolean paused = locked.await(20, TimeUnit.SECONDS);
        if (!paused && writer.isDone()) {
          writer.get();
        }
        assertThat(paused).isTrue();
        var deletion =
            workers.submit(
                () -> {
                  conflict(() -> f.save(f.customer, f.delete(f.customer, customer.recordId(), 0)));
                  return true;
                });
        f.awaitRowLock(f.customer);
        assertThat(deletion.isDone()).isFalse();
        release.countDown();
        assertThat(writer.get(30, TimeUnit.SECONDS)).isTrue();
        assertThat(deletion.get(30, TimeUnit.SECONDS)).isTrue();
        assertThat(f.deleted(f.customer, customer.recordId())).isFalse();
        assertThat(f.count(f.trade)).isEqualTo(1);
      } finally {
        release.countDown();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void targetDeleteWinsAndSourceWriteRollsBack(boolean postgres) throws Exception {
    try (var f = new Fixture(postgres);
        var workers = Executors.newFixedThreadPool(2)) {
      var customer = f.create(f.customer, Map.of());
      f.save(f.customer, customer);
      var trade = f.create(f.trade, Map.of("customer_id", customer.recordId()));
      var locked = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var deletion =
          workers.submit(
              () ->
                  f.heldPublicationWrite(
                      f.customer, f.delete(f.customer, customer.recordId(), 0), locked, release));
      try {
        boolean paused = locked.await(20, TimeUnit.SECONDS);
        if (!paused && deletion.isDone()) {
          deletion.get();
        }
        assertThat(paused).isTrue();
        var writer =
            workers.submit(
                () -> {
                  conflict(() -> f.save(f.trade, trade));
                  return true;
                });
        // FOR UPDATE now fences the FK check inside INSERT, before the later target-liveness
        // SELECT.
        // PostgreSQL reports the INSERT as the waiting query; MySQL reports the locked target
        // table.
        f.awaitRowLock(f.customer, f.trade);
        assertThat(writer.isDone()).isFalse();
        release.countDown();
        assertThat(deletion.get(30, TimeUnit.SECONDS)).isTrue();
        assertThat(writer.get(30, TimeUnit.SECONDS)).isTrue();
        assertThat(f.deleted(f.customer, customer.recordId())).isTrue();
        assertThat(f.count(f.trade)).isZero();
      } finally {
        release.countDown();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void rejectsSelfReferenceButAllowsDistinctRecordsAndUsesActiveSchemaForHistoricalWrites(
      boolean postgres) throws Exception {
    try (var f = new Fixture(postgres, true)) {
      var first = f.create(f.customer, Map.of());
      f.save(f.customer, first);
      conflict(
          () ->
              f.save(
                  f.customer,
                  f.update(
                      f.customer, first.recordId(), 0, Map.of("customer_id", first.recordId()))));
      assertThat(((Number) f.value(f.customer, first.recordId(), "record_version")).longValue())
          .isZero();
      var second = f.create(f.customer, Map.of("customer_id", first.recordId()));
      f.save(f.customer, second);
      f.save(
          f.customer,
          f.update(f.customer, first.recordId(), 0, Map.of("customer_id", second.recordId())));
      assertThat(f.count(f.customer)).isEqualTo(2);
      conflict(() -> f.save(f.customer, f.delete(f.customer, first.recordId(), 1)));
    }
    try (var f = new Fixture(postgres)) {
      var target = f.create(f.customer, Map.of());
      f.save(f.customer, target);
      f.save(f.customer, f.delete(f.customer, target.recordId(), 0));
      var legacySchema =
          new PhysicalSchemaDefinition(
              DnaRuntimeApiVersion.CURRENT, f.trade.activeSchema().fields(), List.of());
      var historical =
          new RuntimeRecordModel(
              f.trade.physicalModelId(),
              "app",
              "trades",
              2,
              f.trade.activeSchema(),
              1,
              legacySchema,
              f.trade.target());
      conflict(
          () -> f.save(historical, f.create(historical, Map.of("customer_id", target.recordId()))));
      assertThat(f.count(f.trade)).isZero();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void refusesMissingOrChangedTargetStorageAndCrossTenantMutationSubjects(boolean postgres)
      throws Exception {
    try (var f = new Fixture(postgres)) {
      var customer = f.create(f.customer, Map.of());
      f.save(f.customer, customer);
      var local = f.customer.target().metadataTarget();
      for (var bindings :
          List.of(
              Map.<String, RuntimeMetadataTarget>of(),
              Map.of(
                  f.customer.physicalModelId(),
                  new RuntimeMetadataTarget(
                      local.dataSourceId(),
                      local.catalogName(),
                      local.schemaName(),
                      "wrong_target")))) {
        var wrong =
            new RuntimeRecordModel(
                f.trade.physicalModelId(),
                "app",
                "trades",
                1,
                f.trade.activeSchema(),
                new PhysicalSchemaTarget(
                    f.trade.physicalModelId(), f.trade.target().metadataTarget(), bindings));
        assertThatThrownBy(
                () -> f.save(wrong, f.create(wrong, Map.of("customer_id", customer.recordId()))))
            .isInstanceOfSatisfying(
                DnaRuntimeException.class,
                failure ->
                    assertThat(failure.errorCode())
                        .isEqualTo(DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE));
      }
      var tenantB =
          f.compiler.mutation(
              f.trade,
              access("tenant-b"),
              new RecordCreateMutation("mismatched", Map.of()),
              Instant.now());
      assertThatThrownBy(() -> f.save(f.trade, tenantB))
          .isInstanceOfSatisfying(
              DnaRuntimeException.class,
              failure ->
                  assertThat(failure.errorCode())
                      .isEqualTo(DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE));
      assertThat(f.count(f.trade)).isZero();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void durableReferenceRejectionCannotBecomeDelayedSuccessfulSubmission(boolean postgres)
      throws Exception {
    try (var f = new Fixture(postgres)) {
      new org.simplepoint.plugin.dna.runtime.service.schema.RecordJournalProvisioner(
              f.schemaBackend)
          .prepare(f.trade.target());
      var customer = f.create(f.customer, Map.of());
      f.save(f.customer, customer);
      f.save(f.customer, f.delete(f.customer, customer.recordId(), 0));
      var statements =
          List.of(
              f.create(f.trade, Map.of()),
              f.create(f.trade, Map.of("customer_id", customer.recordId())));
      var operation =
          new RecordOperationIdentity(
              UUID.randomUUID().toString(),
              "tenant-a",
              "user",
              f.trade.physicalModelId(),
              "rejected-reference",
              "a".repeat(64));
      var admission = new RecordWriteAdmission("tenant-a", 0);
      conflict(() -> f.backend.execute(f.trade, admission, statements, 30_000, operation));
      conflict(() -> f.backend.findResult(f.trade, operation, 30_000));
      assertThat(f.count(f.trade)).isZero();
      // Restore only as a fixture to prove a durable rejection cannot revive when its cause
      // disappears.
      try (var connection = f.dataSource.getConnection();
          var update =
              connection.prepareStatement(
                  "UPDATE "
                      + f.customer.target().metadataTarget().tableName()
                      + " SET deleted_at=NULL WHERE id=?")) {
        update.setString(1, customer.recordId());
        assertThat(update.executeUpdate()).isEqualTo(1);
      }
      conflict(() -> f.backend.execute(f.trade, admission, statements, 30_000, operation));
      assertThat(f.count(f.trade)).isZero();
      var changed =
          new RecordOperationIdentity(
              operation.id(),
              operation.tenantId(),
              operation.actorId(),
              operation.modelId(),
              operation.key(),
              "b".repeat(64));
      assertThatThrownBy(() -> f.backend.execute(f.trade, admission, statements, 30_000, changed))
          .isInstanceOfSatisfying(
              DnaRuntimeException.class,
              failure ->
                  assertThat(failure.errorCode())
                      .isEqualTo(DnaRuntimeErrorCode.RECORD_IDEMPOTENCY_CONFLICT));
      var corrected =
          new RecordOperationIdentity(
              UUID.randomUUID().toString(),
              operation.tenantId(),
              operation.actorId(),
              operation.modelId(),
              "corrected-reference",
              operation.fingerprint());
      assertThat(
              f.backend.execute(f.trade, admission, statements, 30_000, corrected).affectedRows())
          .containsExactly(1, 1);
      // Missing targets rejected by the native FK have the same safe, durable diagnostic as soft
      // deletion.
      var missing = f.create(f.trade, Map.of("customer_id", UUID.randomUUID().toString()));
      var nativeFailure =
          new RecordOperationIdentity(
              UUID.randomUUID().toString(),
              operation.tenantId(),
              operation.actorId(),
              operation.modelId(),
              "missing-reference",
              operation.fingerprint());
      conflict(
          () -> f.backend.execute(f.trade, admission, List.of(missing), 30_000, nativeFailure));
      conflict(() -> f.backend.findResult(f.trade, nativeFailure, 30_000));
      assertThat(f.count(f.trade)).isEqualTo(2);
    }
  }

  private static void conflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(
            DnaRuntimeException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT));
  }

  private static RecordAccessContext access(String tenant) {
    return new RecordAccessContext(
        new RuntimeActor(tenant, "user"),
        "test-resource",
        Set.of("id", "record_version", "customer_id"),
        Set.of("customer_id"),
        Set.of(),
        new DataScopeCondition("ALL", "created_org_dept_id", "created_by", "user", Set.of()),
        "department");
  }

  static final class Fixture implements AutoCloseable {
    final boolean postgres;
    final SimpleDataSource dataSource;
    final RuntimeOwnershipTestFixture.Namespace namespace;
    final RuntimeRecordModel customer;
    final RuntimeRecordModel trade;
    final RuntimeCalciteRecordBackend backend;
    final JdbcPhysicalSchemaBackend schemaBackend;
    final RecordSqlCompiler compiler = new RecordSqlCompiler(new StandardLogicalValueConverter());

    Fixture(boolean postgres) throws Exception {
      this(postgres, false);
    }

    Fixture(boolean postgres, boolean self) throws Exception {
      this(postgres, self, false);
    }

    Fixture(boolean postgres, boolean self, boolean qualification) throws Exception {
      this(postgres, self, qualification, new DefaultCalciteQueryEngine());
    }

    Fixture(
        boolean postgres,
        boolean self,
        boolean qualification,
        org.simplepoint.data.calcite.core.query.CalciteQueryEngine engine)
        throws Exception {
      this(postgres, self, qualification, engine, List.of());
    }

    Fixture(
        boolean postgres,
        boolean self,
        boolean qualification,
        org.simplepoint.data.calcite.core.query.CalciteQueryEngine engine,
        List<LogicalFieldDefinition> extraCustomerFields)
        throws Exception {
      this.postgres = postgres;
      String prefix = "DNA_RUNTIME_TEST_" + (postgres ? "POSTGRES" : "MYSQL");
      String url = System.getenv(prefix + "_URL");
      Assumptions.assumeTrue(url != null && !url.isBlank(), "Disposable real database is required");
      namespace =
          RuntimeOwnershipTestFixture.namespace(
              new DriverManagerDataSource(
                  url, System.getenv(prefix + "_USER"), System.getenv(prefix + "_PASSWORD")),
              postgres);
      dataSource = new SimpleDataSource(namespace.source());
      var codec = new PhysicalSchemaSnapshotCodec(new ObjectMapper().findAndRegisterModules());
      JdbcDatabaseDialect core =
          postgres ? new PostgresqlJdbcDatabaseDialect() : new MysqlJdbcDatabaseDialect();
      RuntimeSchemaDialect dialect =
          postgres
              ? new PostgresqlRuntimeSchemaDialect(codec)
              : new MysqlRuntimeSchemaDialect(codec);
      ManagedRuntimeDataSource handle;
      try (var connection = dataSource.getConnection()) {
        var meta = connection.getMetaData();
        String catalog = connection.getCatalog();
        String schema = postgres ? connection.getSchema() : null;
        final var context =
            new JdbcDatabaseDialect.SupportContext(
                core.code(),
                meta.getDriverName(),
                meta.getDatabaseProductName(),
                meta.getDatabaseProductVersion(),
                catalog,
                schema,
                meta.getIdentifierQuoteString(),
                Map.of());
        final var database =
            new DatabaseIdentity(
                meta.getDatabaseProductName(),
                meta.getDatabaseProductVersion(),
                meta.getDatabaseMajorVersion(),
                meta.getDatabaseMinorVersion());
        String suffix = UUID.randomUUID().toString().replace("-", "");
        var customerTarget =
            new PhysicalSchemaTarget(
                UUID.randomUUID().toString(),
                new RuntimeMetadataTarget("test", catalog, schema, "ref_customer_" + suffix));
        if (self) {
          customerTarget =
              new PhysicalSchemaTarget(
                  customerTarget.physicalModelId(),
                  customerTarget.metadataTarget(),
                  Map.of(customerTarget.physicalModelId(), customerTarget.metadataTarget()));
        }
        final var tradeTarget =
            new PhysicalSchemaTarget(
                "trade-" + suffix,
                new RuntimeMetadataTarget("test", catalog, schema, "ref_trade_" + suffix),
                Map.of(customerTarget.physicalModelId(), customerTarget.metadataTarget()));
        var customerFields = new java.util.ArrayList<LogicalFieldDefinition>();
        customerFields.addAll(extraCustomerFields);
        if (self) {
          customerFields.add(
              new LogicalFieldDefinition(
                  "customer",
                  "customer_id",
                  "Related customer",
                  LogicalFieldType.UUID,
                  true,
                  null,
                  null,
                  null,
                  null));
        }
        if (qualification) {
          customerFields.add(
              new LogicalFieldDefinition(
                  "status",
                  "status",
                  "Status",
                  LogicalFieldType.STRING,
                  true,
                  32,
                  null,
                  null,
                  null));
        }
        var tradeFields = new java.util.ArrayList<LogicalFieldDefinition>();
        tradeFields.add(
            new LogicalFieldDefinition(
                "customer",
                "customer_id",
                "Customer",
                LogicalFieldType.UUID,
                true,
                null,
                null,
                null,
                null));
        if (qualification) {
          tradeFields.add(
              new LogicalFieldDefinition(
                  "note", "note", "Note", LogicalFieldType.STRING, true, 200, null, null, null));
          tradeFields.add(
              new LogicalFieldDefinition(
                  "status",
                  "status",
                  "Status",
                  LogicalFieldType.STRING,
                  true,
                  32,
                  null,
                  null,
                  null));
        }
        var customerSchema =
            new PhysicalSchemaDefinition(
                DnaRuntimeApiVersion.CURRENT,
                customerFields,
                List.of(),
                null,
                true,
                self
                    ? List.of(
                        new LogicalReferenceDefinition(
                            "self-customer", "customer", customerTarget.physicalModelId(), false))
                    : List.of());
        final var tradeSchema =
            new PhysicalSchemaDefinition(
                DnaRuntimeApiVersion.CURRENT,
                tradeFields,
                List.of(),
                null,
                null,
                List.of(
                    new LogicalReferenceDefinition(
                        "trade-customer",
                        "customer",
                        customerTarget.physicalModelId(),
                        false,
                        qualification
                            ? List.of(
                                new org.simplepoint.plugin.dna.runtime.api.schema
                                    .LogicalReferenceField("status", LogicalFieldType.STRING))
                            : List.of())));
        customerTarget =
            RuntimeOwnershipTestFixture.reserveNewModel(dataSource, customerTarget, "tenant-a");
        var registeredTradeTarget =
            RuntimeOwnershipTestFixture.reserveNewModel(dataSource, tradeTarget, "tenant-a");
        customer =
            new RuntimeRecordModel(
                customerTarget.physicalModelId(),
                "app",
                "customers",
                1,
                customerSchema,
                customerTarget);
        trade =
            new RuntimeRecordModel(
                tradeTarget.physicalModelId(),
                "app",
                "trades",
                1,
                tradeSchema,
                registeredTradeTarget);
        for (var model : List.of(customer, trade)) {
          var plan =
              dialect.planInitial(
                  connection,
                  core,
                  context,
                  database,
                  model.target(),
                  "Reference integrity test",
                  model.activeSchema());
          for (var step : plan.steps()) {
            try (var statement = connection.createStatement()) {
              statement.execute(step.statementSql());
            }
          }
        }
        handle =
            new ManagedRuntimeDataSource(
                dataSource, false, context, core, dialect, database, catalog, schema);
      }
      var provider = mock(ManagedRuntimeDataSourceProvider.class);
      when(provider.open(any())).thenReturn(handle);
      backend = new RuntimeCalciteRecordBackend(provider, engine);
      schemaBackend = new JdbcPhysicalSchemaBackend(provider, codec);
      var preparation =
          new RecordWriteFencePreparation(new RecordWriteFenceProvisioner(schemaBackend), backend);
      for (var model : List.of(customer, trade)) {
        preparation.prepare(new RuntimeActor("tenant-a", "publisher"), model.target());
        preparation.prepare(new RuntimeActor("tenant-b", "publisher"), model.target());
      }
    }

    RecordSqlStatement create(RuntimeRecordModel model, Map<String, Object> values) {
      return compiler.mutation(
          model,
          access(model),
          new RecordCreateMutation(UUID.randomUUID().toString(), values),
          Instant.now());
    }

    RecordSqlStatement update(
        RuntimeRecordModel model, String id, long version, Map<String, Object> values) {
      return compiler.mutation(
          model,
          access(model),
          new RecordUpdateMutation(UUID.randomUUID().toString(), id, version, values),
          Instant.now());
    }

    RecordSqlStatement delete(RuntimeRecordModel model, String id, long version) {
      return compiler.mutation(
          model,
          access(model),
          new RecordDeleteMutation(UUID.randomUUID().toString(), id, version),
          Instant.now());
    }

    void save(RuntimeRecordModel model, RecordSqlStatement... statements) {
      backend.execute(model, new RecordWriteAdmission("tenant-a", 0), List.of(statements), 30_000);
    }

    RecordAccessContext access(RuntimeRecordModel model) {
      var fields = new java.util.HashSet<>(model.requestedFieldCodes());
      fields.addAll(Set.of("id", "record_version"));
      return new RecordAccessContext(
          new RuntimeActor("tenant-a", "user"),
          "test-resource",
          fields,
          fields,
          Set.of(),
          new DataScopeCondition("ALL", "created_org_dept_id", "created_by", "user", Set.of()),
          "department");
    }

    boolean heldPublicationWrite(
        RuntimeRecordModel model,
        RecordSqlStatement statement,
        CountDownLatch locked,
        CountDownLatch release,
        RecordReferenceCheck... references) {
      return backend.publication(
          model,
          List.of(),
          new RecordWriteFenceKey("tenant-a", model.physicalModelId()),
          60_000,
          session -> {
            session.lockRows(
                new RecordPublicationLockPlan(List.of(statement), List.of(references), () -> {}));
            session.write(statement);
            locked.countDown();
            try {
              if (!release.await(25, TimeUnit.SECONDS)) {
                throw new AssertionError("Publication transaction was not released");
              }
            } catch (InterruptedException failure) {
              Thread.currentThread().interrupt();
              throw new AssertionError(failure);
            }
            return true;
          });
    }

    Object value(RuntimeRecordModel model, String id, String column) throws Exception {
      try (var connection = dataSource.getConnection();
          var statement =
              connection.prepareStatement(
                  "SELECT "
                      + column
                      + " FROM "
                      + model.target().metadataTarget().tableName()
                      + " WHERE id=?")) {
        statement.setString(1, id);
        try (var rows = statement.executeQuery()) {
          assertThat(rows.next()).isTrue();
          return rows.getObject(1);
        }
      }
    }

    boolean deleted(RuntimeRecordModel model, String id) throws Exception {
      return value(model, id, "deleted_at") != null;
    }

    long count(RuntimeRecordModel model) throws Exception {
      try (var connection = dataSource.getConnection();
          var statement = connection.createStatement();
          var rows =
              statement.executeQuery(
                  "SELECT COUNT(*) FROM " + model.target().metadataTarget().tableName())) {
        rows.next();
        return rows.getLong(1);
      }
    }

    void awaitRowLock(RuntimeRecordModel model) throws Exception {
      awaitRowLock(model, model);
    }

    void awaitRowLock(RuntimeRecordModel model, RuntimeRecordModel waitingStatementModel)
        throws Exception {
      String table = model.target().metadataTarget().tableName();
      String waitingTable = waitingStatementModel.target().metadataTarget().tableName();
      String sql =
          postgres
              ? "SELECT COUNT(*) FROM pg_stat_activity WHERE pid <> pg_backend_pid() AND"
                  + " wait_event_type='Lock' AND query LIKE ?"
              : "SELECT COUNT(*) FROM performance_schema.data_locks WHERE LOCK_STATUS='WAITING' AND"
                  + " OBJECT_NAME=?";
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
      try (var connection = dataSource.getConnection();
          var statement = connection.prepareStatement(sql)) {
        statement.setString(1, postgres ? "%" + waitingTable + "%" : table);
        do {
          try (var rows = statement.executeQuery()) {
            rows.next();
            if (rows.getLong(1) > 0) {
              return;
            }
          }
          Thread.sleep(25);
        } while (System.nanoTime() < until);
      }
      throw new AssertionError("No real target-row lock wait was observed");
    }

    @Override
    public void close() throws Exception {
      try {
        namespace.close();
      } finally {
        dataSource.close();
      }
    }
  }
}
