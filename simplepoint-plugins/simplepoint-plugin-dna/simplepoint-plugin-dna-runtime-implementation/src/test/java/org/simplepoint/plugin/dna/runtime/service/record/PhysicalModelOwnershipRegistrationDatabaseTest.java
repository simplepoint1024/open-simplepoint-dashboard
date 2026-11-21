package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.data.ownership.PhysicalObjectOwner;
import org.simplepoint.data.ownership.PhysicalOwnershipSession;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelRegistrationCommand;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelBindingEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelEntity;

/** Public registry with real PostgreSQL control transactions and both native target databases. */
class PhysicalModelOwnershipRegistrationDatabaseTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void registrationPersistsVerifiedIdentityAndControlRollbackRetainsPhysicalReservation(
      boolean postgres) throws Exception {
    RecordPublicationPatchDatabaseTest.verify(
        postgres ? "POSTGRES" : "MYSQL",
        postgres,
        db -> {
          String namespace = postgres ? db.schema() : db.catalog();
          try {
            RecordMaintenanceControlDatabaseTest.withControl(
                db,
                control -> {
                  RecordMaintenanceControlDatabaseTest.authorize();
                  var parent = db.fixture().parent;
                  var command =
                      new PhysicalModelRegistrationCommand(
                          parent.appId(),
                          "ownership-commit",
                          "关联客户",
                          "default",
                          parent.activeSchema());
                  // The aggregate fixture now installs ownership explicitly. Temporarily hide its
                  // directory to retain the public registration fail-closed assertion, then restore
                  // the same identity and all claims; never erase or replace its ownership history.
                  String directory = qualified(db, "simpoint_physical_object_owners_v1");
                  String hidden = qualified(db, "fixture_hidden_ownership");
                  try (var connection = db.source().getConnection();
                      var statement = connection.createStatement()) {
                    statement.execute(
                        postgres
                            ? "ALTER TABLE " + directory + " RENAME TO fixture_hidden_ownership"
                            : "RENAME TABLE " + directory + " TO " + hidden);
                    try {
                      assertThatThrownBy(() -> control.physical().register(command))
                          .isInstanceOfSatisfying(
                              DnaRuntimeException.class,
                              failure ->
                                  assertThat(failure.errorCode())
                                      .isEqualTo(
                                          DnaRuntimeErrorCode.PHYSICAL_OWNERSHIP_UNAVAILABLE));
                    } finally {
                      statement.execute(
                          postgres
                              ? "ALTER TABLE "
                                  + hidden
                                  + " RENAME TO simpoint_physical_object_owners_v1"
                              : "RENAME TABLE " + hidden + " TO " + directory);
                    }
                  }
                  UUID identity;
                  try (var connection = db.source().getConnection();
                      var session = PhysicalOwnershipSession.open(connection, namespace)) {
                    identity = session.namespaceId();
                  }
                  var created = control.physical().register(command);
                  var binding =
                      control
                          .transactions()
                          .execute(
                              status ->
                                  control
                                      .em()
                                      .createQuery(
                                          "from PhysicalModelBindingEntity where"
                                              + " physicalModelId=:id",
                                          PhysicalModelBindingEntity.class)
                                      .setParameter("id", created.id())
                                      .getSingleResult());
                  assertThat(binding.getPhysicalNamespaceId()).isEqualTo(identity.toString());
                  assertThat(binding.getTenantId()).isEqualTo(db.fixture().intent.key().tenantId());
                  var abandoned = new AtomicReference<PhysicalModelBindingEntity>();
                  control
                      .transactions()
                      .executeWithoutResult(
                          status -> {
                            var result =
                                control
                                    .physical()
                                    .register(
                                        new PhysicalModelRegistrationCommand(
                                            parent.appId(),
                                            "ownership-rollback",
                                            "未提交客户",
                                            "default",
                                            parent.activeSchema()));
                            abandoned.set(
                                control
                                    .em()
                                    .createQuery(
                                        "from PhysicalModelBindingEntity where physicalModelId=:id",
                                        PhysicalModelBindingEntity.class)
                                    .setParameter("id", result.id())
                                    .getSingleResult());
                            status.setRollbackOnly();
                          });
                  var absent =
                      control
                          .transactions()
                          .execute(
                              status ->
                                  control
                                      .em()
                                      .find(
                                          PhysicalModelEntity.class,
                                          abandoned.get().getPhysicalModelId()));
                  assertThat(absent).isNull();
                  try (var connection = db.source().getConnection();
                      var session = PhysicalOwnershipSession.open(connection, namespace)) {
                    assertThat(session.owner(binding.getTableName(), true))
                        .contains(
                            new PhysicalObjectOwner(
                                "dna-runtime", db.fixture().intent.key().tenantId(), created.id()));
                    assertThat(session.owner(abandoned.get().getTableName(), true))
                        .contains(
                            new PhysicalObjectOwner(
                                "dna-runtime",
                                db.fixture().intent.key().tenantId(),
                                abandoned.get().getPhysicalModelId()));
                  }
                });
          } finally {
            RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
          }
        });
  }

  private static String qualified(RecordPublicationPatchDatabaseTest.Database db, String table) {
    return db.core()
        .qualifyName(db.schema() == null ? db.catalog() : null, db.schema(), table, db.context());
  }
}
