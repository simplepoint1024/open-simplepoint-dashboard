package org.simplepoint.plugin.dna.runtime.service.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalBindingInventory;

/** Real control transactions retain cleanup identity without fabricating missing history. */
class PhysicalRetiredCleanupDatabaseTest {
  @Test
  void adoptionRetainsTombstonesAndRecoversAnAlreadyCommittedOperation() throws Exception {
    try (var fixture = new PhysicalOwnershipInventoryDatabaseTest.Fixture()) {
      var plan = cleanup(fixture);
      var before = fixture.jdbc.queryForList("SELECT * FROM simpoint_dna_physical_models");
      var adopter = new PhysicalRetiredCleanupAdopter(fixture.source);
      adopter.review(plan);
      assertThat(adopter.hasReceipt(plan)).isFalse();
      assertThat(
              fixture.jdbc.queryForObject(
                  "SELECT count(*) FROM simpoint_dna_physical_bindings", Integer.class))
          .isZero();
      adopter.adopt(plan);
      assertThat(adopter.hasReceipt(plan)).isTrue();
      adopter.adopt(plan);
      assertThat(fixture.jdbc.queryForList("SELECT * FROM simpoint_dna_physical_models"))
          .isEqualTo(before);
      assertThat(fixture.adapter.inventory()).isEqualTo(plan.inventory());
      var review = fixture.adapter.models(plan.inventory());
      assertThat(review.definitions()).isEmpty();
      assertThat(review.publications()).isEmpty();
      assertThat(review.active(plan.inventory().bindings().getFirst().modelId())).isNull();
      assertThat(
              fixture.jdbc.queryForObject(
                  "SELECT count(*) FROM simpoint_dna_retired_cleanup_evidence", Integer.class))
          .isEqualTo(1);
    }
  }

  @Test
  void changedModelAndUnreviewedOrphansCannotBeSilentlyAdopted() throws Exception {
    try (var fixture = new PhysicalOwnershipInventoryDatabaseTest.Fixture()) {
      var plan = cleanup(fixture);
      var adopter = new PhysicalRetiredCleanupAdopter(fixture.source);
      fixture.jdbc.update("UPDATE simpoint_dna_physical_models SET lock_version=1");
      assertThatThrownBy(() -> adopter.adopt(plan)).hasMessage("DNA_RETIRED_CLEANUP_MODEL_CHANGED");
      assertThat(adopter.hasReceipt(plan)).isFalse();
      fixture.jdbc.update("UPDATE simpoint_dna_physical_models SET lock_version=0");
      String other = fixture.model("other", "RETIRED");
      fixture.jdbc.update(
          "DELETE FROM simpoint_dna_physical_bindings WHERE physical_model_id=?", other);
      assertThatThrownBy(() -> adopter.adopt(plan))
          .hasMessage("DNA_RETIRED_CLEANUP_INCOMPLETE_SCOPE");
      assertThat(adopter.hasReceipt(plan)).isFalse();
    }
  }

  @Test
  void laterBindingCorruptionIsNotRepairedByReplayingCleanup() throws Exception {
    try (var fixture = new PhysicalOwnershipInventoryDatabaseTest.Fixture()) {
      var plan = cleanup(fixture);
      var adopter = new PhysicalRetiredCleanupAdopter(fixture.source);
      adopter.adopt(plan);
      fixture.jdbc.update("UPDATE simpoint_dna_physical_bindings SET table_name='other'");
      assertThat(adopter.hasReceipt(plan)).isTrue();
      assertThatThrownBy(() -> adopter.adopt(plan))
          .hasMessage("DNA_RETIRED_CLEANUP_BINDING_CHANGED");
      var changed =
          new PhysicalRetiredCleanupPlan(
              UUID.randomUUID(), plan.evidenceChecksum(), plan.inventory());
      assertThatThrownBy(() -> adopter.hasReceipt(changed))
          .hasMessage("DNA_RETIRED_CLEANUP_RECEIPT_CONFLICT");
    }
  }

  @Test
  void partialBindingFailureRollsBackAllEvidence() throws Exception {
    try (var fixture = new PhysicalOwnershipInventoryDatabaseTest.Fixture()) {
      var plan = cleanup(fixture);
      var first = plan.inventory().bindings().getFirst();
      // A trigger fails after the binding insert, at the durable evidence boundary.
      fixture.jdbc.execute(
          "CREATE FUNCTION fail_cleanup() RETURNS trigger LANGUAGE plpgsql AS"
              + " $$ BEGIN RAISE EXCEPTION 'injected evidence failure'; END $$");
      fixture.jdbc.execute(
          "CREATE TRIGGER fail_cleanup BEFORE INSERT ON simpoint_dna_retired_cleanup_evidence"
              + " FOR EACH ROW EXECUTE FUNCTION fail_cleanup()");
      var adopter = new PhysicalRetiredCleanupAdopter(fixture.source);
      assertThatThrownBy(() -> adopter.adopt(plan)).hasMessage("DNA_RETIRED_CLEANUP_UNAVAILABLE");
      assertThat(adopter.hasReceipt(plan)).isFalse();
      assertThat(
              fixture.jdbc.queryForObject(
                  "SELECT count(*) FROM simpoint_dna_physical_bindings WHERE id=?",
                  Integer.class,
                  first.bindingId()))
          .isZero();
      fixture.jdbc.execute("DROP TRIGGER fail_cleanup ON simpoint_dna_retired_cleanup_evidence");
      adopter.adopt(plan);
      assertThat(adopter.hasReceipt(plan)).isTrue();
    }
  }

  static PhysicalRetiredCleanupPlan cleanup(
      PhysicalOwnershipInventoryDatabaseTest.Fixture fixture) {
    String model = fixture.model("tenant", "RETIRED");
    fixture.jdbc.update(
        "UPDATE simpoint_dna_physical_models SET deleted_at=now() WHERE id=?", model);
    fixture.jdbc.update(
        "UPDATE simpoint_dna_physical_bindings SET deleted_at=now() WHERE physical_model_id=?",
        model);
    var original = fixture.adapter.inventory().bindings().getFirst();
    String evidence = "a".repeat(64);
    var retained =
        new PhysicalBindingInventory.Binding(
            original.bindingId(),
            original.modelId(),
            original.tenantId(),
            original.target(),
            null,
            original.revision(),
            original.deletedAt(),
            original.model(),
            evidence);
    fixture.jdbc.update(
        "DELETE FROM simpoint_dna_physical_bindings WHERE physical_model_id=?", model);
    return new PhysicalRetiredCleanupPlan(
        UUID.randomUUID(), evidence, new PhysicalBindingInventory(1, List.of(retained)));
  }
}
