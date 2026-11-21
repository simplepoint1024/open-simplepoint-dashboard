package org.simplepoint.plugin.dna.runtime.service.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.ownership.PhysicalAdoptionManifest;
import org.simplepoint.data.ownership.PhysicalNamespaceDescriptor;
import org.simplepoint.data.ownership.PhysicalObjectOwner;
import org.simplepoint.data.ownership.PhysicalOwnershipInspection;
import org.simplepoint.plugin.dna.runtime.service.installation.PhysicalOwnershipInventoryDatabaseTest.Fixture;
import org.simplepoint.plugin.dna.runtime.service.schema.RuntimeInfrastructureTable;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** Native domain transaction tests; native receipt verification belongs to the coordinator. */
class PhysicalOwnershipControlAdoptionDatabaseTest {
  @Test
  void atomicallyAdoptsAllTenantsIncludingRetiredDeletedBindingsAndReplaysExactly()
      throws Exception {
    try (var fixture = new Fixture()) {
      final String live = model(fixture, "tenant-a", "ACTIVE");
      String retired = model(fixture, "tenant-b", "RETIRED");
      fixture.jdbc.update(
          "UPDATE simpoint_dna_physical_bindings SET deleted_at=now()"
              + " WHERE physical_model_id=?",
          retired);
      fixture.jdbc.update(
          "UPDATE simpoint_dna_physical_models SET deleted_at=now() WHERE id=?", retired);
      var plan = plan(fixture);
      var adopter = new PhysicalOwnershipControlAdopter(fixture.source);
      assertThat(adopter.hasReceipt(plan)).isFalse();
      adopter.adopt(plan);
      assertThat(adopter.hasReceipt(plan)).isTrue();
      assertThat(fixture.adapter.inventory().fingerprint())
          .isEqualTo(plan.adoptedInventory().fingerprint());
      var before =
          fixture.jdbc.queryForList("SELECT * FROM simpoint_dna_physical_bindings ORDER BY id");
      adopter.adopt(plan);
      assertThat(
              fixture.jdbc.queryForList("SELECT * FROM simpoint_dna_physical_bindings ORDER BY id"))
          .isEqualTo(before);
      assertThat(fixture.adapter.inventory().bindings())
          .allSatisfy(
              b -> {
                assertThat(b.revision()).isEqualTo(1);
                assertThat(b.model().revision()).isZero();
              });
      assertThat(
              fixture.adapter.inventory().bindings().stream()
                  .filter(b -> b.modelId().equals(live))
                  .findFirst()
                  .orElseThrow()
                  .deletedAt())
          .isNull();
      assertThat(
              fixture.jdbc.queryForObject(
                  "SELECT count(*) FROM simpoint_dna_ownership_adoptions", Integer.class))
          .isEqualTo(1);
    }
  }

  @Test
  void changedModelHistoryOrBindingRefusesAllWrites() throws Exception {
    try (var fixture = new Fixture()) {
      final String model = model(fixture, "tenant", "ACTIVE");
      var plan = plan(fixture);
      fixture.jdbc.update("UPDATE simpoint_dna_schema_migrations SET lock_version=1");
      var adopter = new PhysicalOwnershipControlAdopter(fixture.source);
      assertThatThrownBy(() -> adopter.adopt(plan))
          .hasMessage("DNA_OWNERSHIP_ADOPTION_MODEL_CHANGED");
      assertThat(adopter.hasReceipt(plan)).isFalse();
      assertThat(fixture.adapter.inventory().fingerprint())
          .isEqualTo(plan.models().inventory().fingerprint());
      fixture.jdbc.update(
          "UPDATE simpoint_dna_physical_models SET lock_version=1 WHERE id=?", model);
      assertThatThrownBy(() -> adopter.adopt(plan))
          .hasMessage("DNA_OWNERSHIP_ADOPTION_CONTROL_CHANGED");
    }
  }

  @Test
  void receiptFailureRollsBackEveryNamespaceBinding() throws Exception {
    try (var fixture = new Fixture()) {
      model(fixture, "tenant-a", "ACTIVE");
      model(fixture, "tenant-b", "RETIRED");
      var plan = plan(fixture);
      var failed = new PhysicalOwnershipControlAdopter(failingSource(fixture.source, false));
      assertThatThrownBy(() -> failed.adopt(plan)).hasMessage("DNA_OWNERSHIP_ADOPTION_UNAVAILABLE");
      assertThat(fixture.adapter.inventory().fingerprint())
          .isEqualTo(plan.models().inventory().fingerprint());
      var recovered = new PhysicalOwnershipControlAdopter(fixture.source);
      assertThat(recovered.hasReceipt(plan)).isFalse();
      recovered.adopt(plan);
      assertThat(recovered.hasReceipt(plan)).isTrue();
    }
  }

  @Test
  void lostCommitResponseIsResolvedByReadWithoutReapplyingTheWrite() throws Exception {
    try (var fixture = new Fixture()) {
      model(fixture, "tenant", "ACTIVE");
      var plan = plan(fixture);
      var uncertain = new PhysicalOwnershipControlAdopter(failingSource(fixture.source, true));
      assertThatThrownBy(() -> uncertain.adopt(plan))
          .hasMessage("DNA_OWNERSHIP_ADOPTION_UNAVAILABLE");
      var recovered = new PhysicalOwnershipControlAdopter(fixture.source);
      assertThat(recovered.hasReceipt(plan)).isTrue();
      assertThat(fixture.adapter.inventory().fingerprint())
          .isEqualTo(plan.adoptedInventory().fingerprint());
      recovered.adopt(plan);
      assertThat(fixture.adapter.inventory().bindings().getFirst().revision()).isEqualTo(1);
    }
  }

  @Test
  void receiptRejectsChangedIntentAndCannotRepairLaterCorruption() throws Exception {
    try (var fixture = new Fixture()) {
      model(fixture, "tenant", "ACTIVE");
      var plan = plan(fixture);
      var adopter = new PhysicalOwnershipControlAdopter(fixture.source);
      adopter.adopt(plan);
      String changedReview = "d".repeat(64);
      var changed =
          new PhysicalOwnershipAdoptionPlan(
              plan.operation(),
              changedReview,
              plan.models(),
              plan.targets(),
              plan.manifests().stream()
                  .map(
                      m ->
                          new PhysicalAdoptionManifest(
                              1,
                              m.target(),
                              m.namespaceId(),
                              m.operation(),
                              changedReview,
                              m.claims()))
                  .toList());
      assertThatThrownBy(() -> adopter.hasReceipt(changed))
          .hasMessage("DNA_OWNERSHIP_ADOPTION_RECEIPT_CONFLICT");
      fixture.jdbc.update("UPDATE simpoint_dna_physical_bindings SET physical_namespace_id=NULL");
      assertThat(adopter.hasReceipt(plan)).isTrue();
      assertThatThrownBy(() -> adopter.adopt(plan))
          .hasMessage("DNA_OWNERSHIP_ADOPTION_CONTROL_CHANGED");
      assertThat(fixture.adapter.inventory().bindings().getFirst().namespaceId()).isNull();
    }
  }

  @Test
  void planRejectsMissingSharedClaimsAndLegacyNamespaceCoverage() throws Exception {
    try (var fixture = new Fixture()) {
      model(fixture, "tenant", "ACTIVE");
      var plan = plan(fixture);
      assertThatThrownBy(
              () ->
                  new PhysicalOwnershipAdoptionPlan(
                      plan.operation(),
                      plan.reviewFingerprint(),
                      plan.models(),
                      plan.targets(),
                      List.of()))
          .hasMessage("DNA_OWNERSHIP_ADOPTION_PLAN_INVALID");
      var original = plan.manifests().getFirst();
      var missing =
          new PhysicalAdoptionManifest(
              1,
              original.target(),
              original.namespaceId(),
              original.operation(),
              original.reviewFingerprint(),
              original.claims().subList(1, original.claims().size()));
      assertThatThrownBy(
              () ->
                  new PhysicalOwnershipAdoptionPlan(
                      plan.operation(),
                      plan.reviewFingerprint(),
                      plan.models(),
                      plan.targets(),
                      List.of(missing)))
          .hasMessage("DNA_OWNERSHIP_ADOPTION_PLAN_INVALID");
    }
  }

  private static String model(Fixture fixture, String tenant, String state) {
    String id = fixture.model(tenant, state);
    PhysicalOwnershipModelDatabaseTest.definition(fixture, id, tenant, 1);
    PhysicalOwnershipModelDatabaseTest.publication(fixture, id, tenant);
    return id;
  }

  private static PhysicalOwnershipAdoptionPlan plan(Fixture fixture) {
    var inventory = fixture.adapter.inventory();
    var models = fixture.adapter.models(inventory);
    var operation = UUID.randomUUID();
    var namespace = UUID.randomUUID();
    var descriptor =
        new PhysicalNamespaceDescriptor(
            "postgresql", "server", "database", "1", "public", "2", "a".repeat(64));
    var inspection = new PhysicalOwnershipInspection(descriptor, namespace, true);
    var targets =
        new PhysicalBindingTargetReview(
            1,
            inventory.fingerprint(),
            inventory.bindings().stream()
                .map(
                    b ->
                        new PhysicalBindingTargetReview.Binding(
                            b.bindingId(), inspection, b.target().tableName()))
                .toList());
    var claims = new ArrayList<PhysicalAdoptionManifest.Claim>();
    for (var binding : inventory.bindings()) {
      claims.add(
          new PhysicalAdoptionManifest.Claim(
              binding.target().tableName(),
              new PhysicalObjectOwner("dna-runtime", binding.tenantId(), binding.modelId()),
              true,
              "b".repeat(64)));
    }
    for (var table : RuntimeInfrastructureTable.values()) {
      claims.add(
          new PhysicalAdoptionManifest.Claim(table.table(), table.owner(), false, "c".repeat(64)));
    }
    String review = "e".repeat(64);
    return new PhysicalOwnershipAdoptionPlan(
        operation,
        review,
        models,
        targets,
        List.of(new PhysicalAdoptionManifest(1, descriptor, namespace, operation, review, claims)));
  }

  private static DataSource failingSource(DataSource source, boolean afterCommit) {
    return new AbstractDataSource() {
      @Override
      public Connection getConnection() throws SQLException {
        var real = source.getConnection();
        var once = new AtomicBoolean();
        return (Connection)
            Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                  if (!afterCommit
                      && method.getName().equals("prepareStatement")
                      && args[0]
                          .toString()
                          .startsWith("INSERT INTO simpoint_dna_ownership_adoptions")) {
                    throw new SQLException("injected receipt failure", "08006");
                  }
                  try {
                    var result = method.invoke(real, args);
                    if (afterCommit
                        && method.getName().equals("commit")
                        && once.compareAndSet(false, true)) {
                      throw new SQLException("injected lost commit reply", "08006");
                    }
                    return result;
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
      }

      @Override
      public Connection getConnection(String user, String password) {
        throw new UnsupportedOperationException();
      }
    };
  }
}
