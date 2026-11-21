package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.simplepoint.plugin.dna.runtime.service.record.SchemaPublicationPersistenceDatabaseTest.plan;
import static org.simplepoint.plugin.dna.runtime.service.record.SchemaPublicationPersistenceDatabaseTest.provider;
import static org.simplepoint.plugin.dna.runtime.service.record.SchemaPublicationPersistenceDatabaseTest.service;
import static org.simplepoint.plugin.dna.runtime.service.record.SchemaPublicationPersistenceDatabaseTest.version;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationStatus;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelRegistrationCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelVersionCommand;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.OwnedModelRelation;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.api.service.RuntimeMetadataCacheInvalidator;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelEntity;
import org.simplepoint.plugin.dna.runtime.service.record.RecordMaintenanceControlDatabaseTest.ControlFixture;
import org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationPatchDatabaseTest.Database;
import org.simplepoint.plugin.dna.runtime.service.record.SchemaPublicationPersistenceDatabaseTest.ObservedBackend;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaSnapshotCodec;

/** Real registration, control catalog, native DDL and recovery for N:1 and 1:1 publication. */
class SchemaReferencePublicationDatabaseTest {
  @ParameterizedTest
  @CsvSource({"POSTGRES,true,false", "POSTGRES,true,true", "MYSQL,false,false", "MYSQL,false,true"})
  void publishesAndRecoversReferences(String database, boolean postgres, boolean unique)
      throws Exception {
    RecordPublicationPatchDatabaseTest.verify(
        database,
        postgres,
        true,
        db ->
            RecordMaintenanceControlDatabaseTest.withControl(
                db, control -> verify(db, control, unique)));
  }

  @ParameterizedTest
  @CsvSource({"POSTGRES,true", "MYSQL,false"})
  void tightensReferenceUniquenessAndNullabilityAfterDataRepair(String database, boolean postgres)
      throws Exception {
    RecordPublicationPatchDatabaseTest.verify(
        database, postgres, true,
        db -> RecordMaintenanceControlDatabaseTest.withControl(db, control -> {
          RecordMaintenanceControlDatabaseTest.authorize();
          var publication = service(
              db, control, backend(db, false), mock(RuntimeMetadataCacheInvalidator.class));
          var target = control.physical().register(new PhysicalModelRegistrationCommand(
              "relations-app", "customer", "客户", "default",
              new PhysicalSchemaDefinition(
                  DnaRuntimeApiVersion.CURRENT, List.of(), List.of(), null, true)));
          publication.publish(target.id(), new SchemaPublicationCommand(1, target.lockVersion()));
          var manyToOne = new LogicalReferenceDefinition("trade-customer", "customer", target.id(), false);
          var oneToOne = new LogicalReferenceDefinition("trade-customer", "customer", target.id(), true);
          var trade = control.physical().register(new PhysicalModelRegistrationCommand(
              "relations-app", "trade", "交易", "default", schema("customer_id", manyToOne)));
          String model = trade.id();
          publication.publish(model, new SchemaPublicationCommand(1, trade.lockVersion()));
          String table = table(db, control, model);
          var customers = new PublishedReferenceRecordFixture(db, control, target);
          String first = customers.create();
          String second = customers.create();
          insert(db, table, "tenant-a", UUID.randomUUID().toString(), "customer_id", first);
          String duplicate = UUID.randomUUID().toString();
          insert(db, table, "tenant-a", duplicate, "customer_id", first);

          var unique = control.physical().createVersion(model, new PhysicalModelVersionCommand(
              version(control, model), schema("customer_id", oneToOne)));
          assertThat(control.physical().get(model).status()).isEqualTo(PhysicalModelStatus.ACTIVE);
          reject(() -> publication.publish(model,
              new SchemaPublicationCommand(unique.version(), version(control, model))),
              DnaRuntimeErrorCode.SCHEMA_PRECHECK_FAILED);
          assertThat(control.physical().get(model).activeVersion()).isEqualTo(1);
          assertThat(control.physical().get(model).status()).isEqualTo(PhysicalModelStatus.ACTIVE);
          setReference(db, table, duplicate, null);
          var failedUnique = publication.getByTargetVersion(model, unique.version());
          assertThat(publication.retry(failedUnique.migrationId(), version(control, model)).status())
              .isEqualTo(SchemaMigrationStatus.ACTIVE);
          assertThatThrownBy(() ->
              insert(db, table, "tenant-a", UUID.randomUUID().toString(), "customer_id", first))
              .isInstanceOf(SQLException.class);

          var required = control.physical().createVersion(model, new PhysicalModelVersionCommand(
              version(control, model), schema("customer_id", oneToOne, false)));
          reject(() -> publication.publish(model,
              new SchemaPublicationCommand(required.version(), version(control, model))),
              DnaRuntimeErrorCode.SCHEMA_PRECHECK_FAILED);
          assertThat(control.physical().get(model).activeVersion()).isEqualTo(unique.version());
          assertThat(control.physical().get(model).status()).isEqualTo(PhysicalModelStatus.ACTIVE);
          setReference(db, table, duplicate, second);
          var failedRequired = publication.getByTargetVersion(model, required.version());
          assertThat(publication.retry(failedRequired.migrationId(), version(control, model)).status())
              .isEqualTo(SchemaMigrationStatus.ACTIVE);
          assertThat(control.physical().get(model).activeVersion()).isEqualTo(required.version());
          assertThatThrownBy(() -> setReference(db, table, duplicate, null))
              .isInstanceOf(SQLException.class);
        }));
  }

  private void verify(Database db, ControlFixture control, boolean unique) throws Exception {
    RecordMaintenanceControlDatabaseTest.authorize();
    var backend = backend(db, false);
    var publication = service(db, control, backend, mock(RuntimeMetadataCacheInvalidator.class));
    var customerSchema =
        new PhysicalSchemaDefinition(
            DnaRuntimeApiVersion.CURRENT, List.of(), List.of(), null, true);
    var customer =
        control
            .physical()
            .register(
                new PhysicalModelRegistrationCommand(
                    "relations-app", "customer", "客户", "default", customerSchema));
    var activeCustomer =
        publication.publish(customer.id(), new SchemaPublicationCommand(1, customer.lockVersion()));
    assertThat(activeCustomer.status()).isEqualTo(SchemaMigrationStatus.ACTIVE);

    var relation =
        new LogicalReferenceDefinition("trade-customer", "customer", customer.id(), unique);
    var initial = schema("customer_id", relation);
    var trade =
        control
            .physical()
            .register(
                new PhysicalModelRegistrationCommand(
                    "relations-app", "trade", "客户交易", "default", initial));
    var active =
        publication.publish(trade.id(), new SchemaPublicationCommand(1, trade.lockVersion()));
    assertThat(active.status()).isEqualTo(SchemaMigrationStatus.ACTIVE);
    assertThat(definitions(control, trade.id())).isEqualTo(1);
    assertThat(guards(control, active.migrationId(), false)).isZero();
    assertThat(guards(control, active.migrationId(), true)).isEqualTo(2);

    String customerTable = table(db, control, customer.id());
    String tradeTable = table(db, control, trade.id());
    var records = new PublishedReferenceRecordFixture(db, control, customer);
    String customerId = records.create();
    insert(db, tradeTable, "tenant-a", UUID.randomUUID().toString(), "customer_id", customerId);
    assertThatThrownBy(
            () ->
                insert(
                    db,
                    tradeTable,
                    "tenant-b",
                    UUID.randomUUID().toString(),
                    "customer_id",
                    customerId))
        .isInstanceOf(SQLException.class);
    if (unique) {
      assertThatThrownBy(
              () ->
                  insert(
                      db,
                      tradeTable,
                      "tenant-a",
                      UUID.randomUUID().toString(),
                      "customer_id",
                      customerId))
          .isInstanceOf(SQLException.class);
    } else {
      insert(db, tradeTable, "tenant-a", UUID.randomUUID().toString(), "customer_id", customerId);
    }

    var next =
        control
            .physical()
            .createVersion(
                trade.id(),
                new PhysicalModelVersionCommand(
                    version(control, trade.id()), schema("client_id", relation)));
    var interrupted = backend(db, true);
    var first = service(db, control, interrupted, mock(RuntimeMetadataCacheInvalidator.class));
    reject(
        () ->
            first.publish(
                trade.id(),
                new SchemaPublicationCommand(next.version(), version(control, trade.id()))),
        DnaRuntimeErrorCode.SCHEMA_PUBLICATION_INTERRUPTED);
    var failed = first.getByTargetVersion(trade.id(), next.version());
    assertThat(failed.status()).isEqualTo(SchemaMigrationStatus.FAILED);
    assertThat(interrupted.executed).hasSize(1);
    String durablePlan = plan(control, failed.migrationId());
    assertThat(durablePlan).isNotBlank();
    assertThat(guards(control, failed.migrationId(), false)).isEqualTo(2);
    reject(() -> records.delete(customerId), DnaRuntimeErrorCode.SCHEMA_MIGRATION_CONFLICT);
    // The removed FK must not leave an unguarded target between processes or retries.
    reject(
        () ->
            control
                .physical()
                .createVersion(
                    customer.id(),
                    new PhysicalModelVersionCommand(
                        version(control, customer.id()), customerSchema)),
        DnaRuntimeErrorCode.SCHEMA_MIGRATION_CONFLICT);

    var resumed = backend(db, false);
    var second = service(db, control, resumed, mock(RuntimeMetadataCacheInvalidator.class));
    var completed = second.retry(failed.migrationId(), version(control, trade.id()));
    assertThat(completed.status()).isEqualTo(SchemaMigrationStatus.ACTIVE);
    assertThat(resumed.executed).isNotEmpty().doesNotContain(interrupted.executed.getFirst());
    assertThat(plan(control, failed.migrationId())).isEqualTo(durablePlan);
    assertThat(definitions(control, trade.id())).isEqualTo(2);
    assertThat(guards(control, failed.migrationId(), false)).isZero();
    reject(() -> records.delete(customerId), DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT);
    Integer activeVersion =
        control
            .transactions()
            .execute(
                status ->
                    control.em().find(PhysicalModelEntity.class, trade.id()).getActiveVersion());
    assertThat(activeVersion).isEqualTo(next.version());
    assertThatThrownBy(
            () ->
                insert(
                    db,
                    tradeTable,
                    "tenant-b",
                    UUID.randomUUID().toString(),
                    "client_id",
                    customerId))
        .isInstanceOf(SQLException.class);
    try (var connection = db.source().getConnection();
        var statement =
            connection.prepareStatement("DELETE FROM " + customerTable + " WHERE id=?")) {
      statement.setString(1, customerId);
      assertThatThrownBy(statement::executeUpdate).isInstanceOf(SQLException.class);
    }
    String detailTable = verifyOwnedReference(db, control, trade.id(), customer.id(), unique);
    if (unique) {
      verifyOwnedUniqueness(db, tradeTable, detailTable, customerId);
    }
  }

  private String verifyOwnedReference(
      Database db, ControlFixture control, String root, String target, boolean unique)
      throws Exception {
    var schema =
        new PhysicalSchemaDefinition(
            DnaRuntimeApiVersion.CURRENT,
            List.of(
                new LogicalFieldDefinition(
                    "owned_parent",
                    "parent_record_id",
                    "所属交易",
                    LogicalFieldType.UUID,
                    false,
                    null,
                    null,
                    null,
                    null),
                new LogicalFieldDefinition(
                    "customer",
                    "customer_id",
                    "客户",
                    LogicalFieldType.UUID,
                    true,
                    null,
                    null,
                    null,
                    null)),
            List.of(),
            new OwnedModelRelation(root, "owned_parent"),
            null,
            List.of(new LogicalReferenceDefinition("detail-customer", "customer", target, unique)));
    var detail =
        control
            .physical()
            .register(
                new PhysicalModelRegistrationCommand(
                    "relations-app", "trade-detail", "交易明细", "default", schema));
    var interrupted = backend(db, true);
    var first = service(db, control, interrupted, mock(RuntimeMetadataCacheInvalidator.class));
    reject(
        () -> first.publish(detail.id(), new SchemaPublicationCommand(1, detail.lockVersion())),
        DnaRuntimeErrorCode.SCHEMA_PUBLICATION_INTERRUPTED);
    var failed = first.getByTargetVersion(detail.id(), 1);
    assertThat(guards(control, failed.migrationId(), false)).isEqualTo(3);
    var currentRoot = control.physical().get(root);
    reject(
        () ->
            control
                .physical()
                .createVersion(
                    root,
                    new PhysicalModelVersionCommand(
                        currentRoot.lockVersion(), currentRoot.latestSchema().schema())),
        DnaRuntimeErrorCode.SCHEMA_MIGRATION_CONFLICT);
    String savedPlan = plan(control, failed.migrationId());
    var resumed =
        service(db, control, backend(db, false), mock(RuntimeMetadataCacheInvalidator.class));
    assertThat(resumed.retry(failed.migrationId(), version(control, detail.id())).status())
        .isEqualTo(SchemaMigrationStatus.ACTIVE);
    assertThat(plan(control, failed.migrationId())).isEqualTo(savedPlan);
    assertThat(guards(control, failed.migrationId(), false)).isZero();
    String retainedRoot =
        control
            .transactions()
            .execute(
                status ->
                    control
                        .em()
                        .createQuery(
                            "select r.sourceRootModelId from PhysicalRelationDefinitionEntity r"
                                + " where r.sourceModelId=:model",
                            String.class)
                        .setParameter("model", detail.id())
                        .getSingleResult());
    assertThat(retainedRoot).isEqualTo(root);
    return table(db, control, detail.id());
  }

  private static void verifyOwnedUniqueness(
      Database db, String rootTable, String detailTable, String target) throws SQLException {
    String left = UUID.randomUUID().toString();
    String right = UUID.randomUUID().toString();
    insert(db, rootTable, "tenant-a", left, null, null);
    insert(db, rootTable, "tenant-a", right, null, null);
    insertDetail(db, detailTable, left, null);
    insertDetail(db, detailTable, right, null);
    String reference = insertDetail(db, detailTable, left, target);
    assertThatThrownBy(() -> insertDetail(db, detailTable, right, target))
        .isInstanceOf(SQLException.class);

    try (var connection = db.source().getConnection();
        var delete = connection.prepareStatement(
            "UPDATE " + detailTable + " SET deleted_at=CURRENT_TIMESTAMP WHERE id=?");
        var restore = connection.prepareStatement(
            "UPDATE " + detailTable + " SET deleted_at=NULL WHERE id=?")) {
      delete.setString(1, reference);
      assertThat(delete.executeUpdate()).isEqualTo(1);
      assertThatThrownBy(() -> insertDetail(db, detailTable, right, target))
          .isInstanceOf(SQLException.class);
      restore.setString(1, reference);
      assertThat(restore.executeUpdate()).isEqualTo(1);
    }
  }

  private static String insertDetail(Database db, String table, String parent, String target)
      throws SQLException {
    String id = UUID.randomUUID().toString();
    try (var connection = db.source().getConnection();
        var statement = connection.prepareStatement(
            "INSERT INTO " + table
                + " (id,tenant_id,created_by,created_at,updated_by,updated_at,parent_record_id,customer_id)"
                + " VALUES (?,'tenant-a','publisher',CURRENT_TIMESTAMP,'publisher',CURRENT_TIMESTAMP,?,?)")) {
      statement.setString(1, id);
      statement.setString(2, parent);
      statement.setString(3, target);
      assertThat(statement.executeUpdate()).isEqualTo(1);
      return id;
    }
  }

  private static ObservedBackend backend(Database db, boolean interrupt) throws Exception {
    return new ObservedBackend(
        provider(db), new PhysicalSchemaSnapshotCodec(new ObjectMapper()), interrupt);
  }

  private static PhysicalSchemaDefinition schema(
      String column, LogicalReferenceDefinition relation) {
    return schema(column, relation, true);
  }

  private static PhysicalSchemaDefinition schema(
      String column, LogicalReferenceDefinition relation, boolean nullable) {
    return new PhysicalSchemaDefinition(
        DnaRuntimeApiVersion.CURRENT,
        List.of(
            new LogicalFieldDefinition(
                "customer", column, "客户", LogicalFieldType.UUID, nullable, null, null, null, null)),
        List.of(),
        null,
        true,
        List.of(relation));
  }

  private static void setReference(Database db, String table, String id, String target)
      throws SQLException {
    try (var connection = db.source().getConnection();
        var statement = connection.prepareStatement(
            "UPDATE " + table + " SET customer_id=? WHERE id=? AND tenant_id='tenant-a'")) {
      statement.setString(1, target);
      statement.setString(2, id);
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
  }

  private static long definitions(ControlFixture control, String model) {
    return control
        .transactions()
        .execute(
            status ->
                control
                    .em()
                    .createQuery(
                        "select count(r) from PhysicalRelationDefinitionEntity r where"
                            + " r.sourceModelId=:model",
                        Long.class)
                    .setParameter("model", model)
                    .getSingleResult());
  }

  private static long guards(ControlFixture control, String migration, boolean released) {
    return control
        .transactions()
        .execute(
            status ->
                control
                    .em()
                    .createQuery(
                        "select count(r) from PhysicalRelationGuardEntity r where"
                            + " r.migrationId=:migration and r.released=:released",
                        Long.class)
                    .setParameter("migration", migration)
                    .setParameter("released", released)
                    .getSingleResult());
  }

  private static String table(Database db, ControlFixture control, String model) {
    String name =
        control
            .transactions()
            .execute(
                status ->
                    control
                        .em()
                        .createQuery(
                            "select b.tableName from PhysicalModelBindingEntity b where"
                                + " b.physicalModelId=:model",
                            String.class)
                        .setParameter("model", model)
                        .getSingleResult());
    String namespace = db.schema() == null ? db.catalog() : db.schema();
    String quote = db.schema() == null ? "`" : "\"";
    return quote + namespace + quote + "." + quote + name + quote;
  }

  private static void insert(
      Database db, String table, String tenant, String id, String field, String value)
      throws SQLException {
    String columns =
        "id,tenant_id,created_by,created_at,updated_by,updated_at"
            + (field == null ? "" : "," + field);
    String values =
        "?,?,'publisher',CURRENT_TIMESTAMP,'publisher',CURRENT_TIMESTAMP"
            + (field == null ? "" : ",?");
    try (var connection = db.source().getConnection();
        var statement =
            connection.prepareStatement(
                "INSERT INTO " + table + " (" + columns + ") VALUES (" + values + ")")) {
      statement.setString(1, id);
      statement.setString(2, tenant);
      if (field != null) {
        statement.setString(3, value);
      }
      statement.executeUpdate();
    }
  }

  private static void reject(Runnable action, DnaRuntimeErrorCode code) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            DnaRuntimeException.class, failure -> assertThat(failure.errorCode()).isEqualTo(code));
  }
}
