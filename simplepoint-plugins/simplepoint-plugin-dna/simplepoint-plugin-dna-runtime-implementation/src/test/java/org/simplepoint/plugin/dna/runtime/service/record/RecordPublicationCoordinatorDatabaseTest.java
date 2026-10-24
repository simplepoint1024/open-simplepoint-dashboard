package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationPatchDatabaseTest.snapshot;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationPatchDatabaseTest.transaction;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationPatchDatabaseTest.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelResourceBindingCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelResourceProjectionCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelResourceBindingEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalResourceProjectionEntity;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelBindingRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelVersionRepository;
import org.simplepoint.plugin.dna.runtime.service.record.RecordMaintenanceControlDatabaseTest.ControlFixture;
import org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationPatchDatabaseTest.Database;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

/** Actual control-lock -> metadata -> target-transaction composition; public APP service activation remains guarded. */
class RecordPublicationCoordinatorDatabaseTest {
  private static final RuntimeActor ACTOR = new RuntimeActor("tenant-a", "publisher");

  @Test
  void coordinatesPostgresqlMetadataAndPostgresqlBatches() throws Exception {
    RecordPublicationPatchDatabaseTest.verify("POSTGRES", true,
        db -> RecordMaintenanceControlDatabaseTest.withControl(db, control -> verify(db, control)));
  }

  @Test
  void coordinatesPostgresqlMetadataAndMysqlBatches() throws Exception {
    RecordPublicationPatchDatabaseTest.verify("MYSQL", false,
        db -> RecordMaintenanceControlDatabaseTest.withControl(db, control -> verify(db, control)));
  }

  private static void verify(Database db, ControlFixture control) throws Exception {
    RecordMaintenanceControlDatabaseTest.authorize();
    var fixture = db.fixture();
    var repositories = new JpaRepositoryFactory(control.em());
    var resolver = new RuntimeRecordModelResolver(repositories.getRepository(PhysicalModelRepository.class),
        repositories.getRepository(PhysicalModelBindingRepository.class), repositories.getRepository(PhysicalModelVersionRepository.class), fixture.codec);
    var metadata = new RecordPublicationMetadata(control.em(), resolver, fixture.codec);
    var batches = new RecordPublicationBatchExecutor(fixture.codec, fixture.sql);
    var coordinator = new RecordPublicationCoordinator(control.control(), metadata, db.backend(), batches, new RecordRequestFingerprint(new ObjectMapper()));
    var command = fixture.intent.command();
    var ids = fixture.models.keySet();
    assertThatThrownBy(() -> metadata.resolve(ACTOR, command, Map.of())).isInstanceOf(IllegalStateException.class);
    control.transactions().executeWithoutResult(status -> {
      var unlocked = Map.of(fixture.parent.physicalModelId(), control.em().find(PhysicalModelEntity.class, fixture.parent.physicalModelId()),
          fixture.child.physicalModelId(), control.em().find(PhysicalModelEntity.class, fixture.child.physicalModelId()));
      assertThatThrownBy(() -> metadata.resolve(ACTOR, command, unlocked)).isInstanceOf(DnaRuntimeException.class);
    });
    control.resource().project(new PhysicalModelResourceProjectionCommand(fixture.parent.physicalModelId(), "page-a", "app:a", 3));
    control.resource().project(new PhysicalModelResourceProjectionCommand(fixture.parent.physicalModelId(), "page-b", "app:b", 9));
    control.resource().bind(new PhysicalModelResourceBindingCommand(fixture.child.physicalModelId(), "child-legacy", version(control, fixture.child)));
    control.transactions().executeWithoutResult(status -> {
      legacy(control, fixture.parent.physicalModelId(), "legacy-without-projection", ACTOR.tenantId(), false);
      legacy(control, fixture.parent.physicalModelId(), "retired", ACTOR.tenantId(), true);
      legacy(control, fixture.parent.physicalModelId(), "foreign-tenant", "tenant-b", false);
    });
    var initial = control.control().execute(ACTOR.tenantId(), ids, locked -> metadata.resolve(ACTOR, command, locked));
    assertThat(initial.resources().get(fixture.parent.physicalModelId())).containsOnlyKeys("page-a", "page-b", "legacy-without-projection");
    assertThat(initial.resources().get(fixture.parent.physicalModelId()).get("legacy-without-projection").projection()).isNull();
    assertThat(initial.resources().get(fixture.child.physicalModelId()).get("child-legacy").projection().generation()).isZero();
    var frozen = freeze(fixture, command, initial);
    batches.validateMetadata(frozen, initial.models());
    // A changed lower generation is detected even though the maximum (page-b=9) stays unchanged.
    control.resource().project(new PhysicalModelResourceProjectionCommand(fixture.parent.physicalModelId(), "page-a", "app:a", 4));
    var changed = control.control().execute(ACTOR.tenantId(), ids, locked -> metadata.resolve(ACTOR, command, locked));
    assertThatThrownBy(() -> batches.validateMetadata(frozen, changed.models())).isInstanceOf(DnaRuntimeException.class);
    var frozenAgain = freeze(fixture, command, changed);
    control.resource().unbind(new PhysicalModelResourceBindingCommand(fixture.child.physicalModelId(), "child-legacy", version(control, fixture.child)));
    var unbound = control.control().execute(ACTOR.tenantId(), ids, locked -> metadata.resolve(ACTOR, command, locked));
    assertThatThrownBy(() -> batches.validateMetadata(frozenAgain, unbound.models())).isInstanceOf(DnaRuntimeException.class);
    // An inconsistent active binding to an offline projection fails closed, rather than disappearing from the snapshot.
    control.transactions().executeWithoutResult(status -> projection(control, "page-a").setFormallyOffline(true));
    assertThatThrownBy(() -> control.control().execute(ACTOR.tenantId(), ids, locked -> metadata.resolve(ACTOR, command, locked)))
        .isInstanceOfSatisfying(DnaRuntimeException.class, e -> assertThat(e.errorCode()).isEqualTo(DnaRuntimeErrorCode.RESOURCE_PROJECTION_SUPERSEDED));
    control.transactions().executeWithoutResult(status -> projection(control, "page-a").setFormallyOffline(false));
    try (var connection = transaction(db)) {
      for (var plan : fixture.seed()) assertThat(update(db, connection, plan)).isEqualTo(1);
      connection.commit();
    }
    var entry = coordinator.advance(ACTOR, command, false);
    assertThat(entry.intent().contractVersion()).isEqualTo(2);
    assertThat(entry.intent().resources().get(fixture.parent.physicalModelId()).get("page-a").projection().generation()).isEqualTo(4);
    assertThat(entry.checkpoint().phase()).isEqualTo(RecordPublicationCheckpoint.Phase.STAGING);
    assertThat(entry.checkpoint().staged()).isZero();
    var originalIntent = entry.intent();
    for (String resource : List.of("legacy-without-projection", "missing-binding")) {
      var binding = new PhysicalModelResourceBindingCommand(fixture.parent.physicalModelId(), resource, version(control, fixture.parent));
      assertThatThrownBy(() -> {
        if (resource.equals("missing-binding")) control.resource().unbind(binding);
        else control.resource().bind(binding);
      }).isInstanceOfSatisfying(DnaRuntimeException.class,
          e -> assertThat(e.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_MAINTENANCE_IN_PROGRESS));
      var count = control.transactions().execute(status -> control.em().createQuery(
          "select count(p) from PhysicalResourceProjectionEntity p where p.tenantId = :tenant and p.resourceCode = :resource", Long.class)
          .setParameter("tenant", ACTOR.tenantId()).setParameter("resource", resource).getSingleResult());
      assertThat(count).isZero();
    }
    assertThatThrownBy(() -> control.resource().project(
        new PhysicalModelResourceProjectionCommand(fixture.parent.physicalModelId(), "page-a", "app:a", 5)))
        .isInstanceOfSatisfying(DnaRuntimeException.class, e -> assertThat(e.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_MAINTENANCE_IN_PROGRESS));
    for (int attempt = 0; entry.checkpoint().progress() != null && attempt < 6; attempt++) {
      entry = coordinator.advance(new RuntimeActor(ACTOR.tenantId(), "recovery-actor"), command, false);
      assertThat(entry.intent()).isEqualTo(originalIntent);
    }
    assertThat(entry.checkpoint().phase()).isEqualTo(RecordPublicationCheckpoint.Phase.COMMITTED);
    var installed = installed(db, 1);
    assertThat(installed.revision()).isEqualTo(1);
    assertThat(installed.policy().definition()).isEqualTo(command.calculation());
    control.resource().project(new PhysicalModelResourceProjectionCommand(fixture.parent.physicalModelId(), "page-a", "app:a", 5));
    assertThat(coordinator.advance(ACTOR, command, false)).isEqualTo(entry);
    assertThat(coordinator.advance(ACTOR, command, true)).isEqualTo(entry);
    Map<String, List<Map<String, String>>> beforeReplacement;
    try (var connection = transaction(db)) { beforeReplacement = snapshot(db, connection); }
    var replacement = command(command, 1, 2, "42.1250");
    entry = coordinator.advance(ACTOR, replacement, false);
    assertThat(entry.intent().previousCalculation()).isEqualTo(new RecordPublicationIntent.InstalledCalculation(1,
        RecordCalculationStore.policyChecksum(installed.policy())));
    for (int attempt = 0; entry.checkpoint().applied() < 1 && attempt < 4; attempt++) entry = coordinator.advance(ACTOR, replacement, false);
    assertThat(entry.checkpoint().applied()).isEqualTo(1);
    assertThat(installed(db, 2)).isEqualTo(installed);
    entry = coordinator.advance(ACTOR, replacement, true);
    assertThatThrownBy(() -> coordinator.advance(ACTOR, replacement, false)).isInstanceOf(DnaRuntimeException.class);
    assertThat(read(db, 2)).isEqualTo(entry);
    for (int attempt = 0; entry.checkpoint().progress() != null && attempt < 4; attempt++) entry = coordinator.advance(ACTOR, replacement, true);
    assertThat(entry.checkpoint().phase()).isEqualTo(RecordPublicationCheckpoint.Phase.CANCELLED);
    assertThat(installed(db, 2)).isEqualTo(installed);
    try (var connection = transaction(db)) { assertThat(snapshot(db, connection)).isEqualTo(beforeReplacement); }
    // Runtime failure after claim is recorded in a separate guarded attempt, with no business writes.
    var failing = command(command, 2, 3, "1 / (LEN([source]) - LEN([source]))");
    coordinator.advance(ACTOR, failing, false);
    assertThatThrownBy(() -> coordinator.advance(ACTOR, failing, false)).isInstanceOf(DnaRuntimeException.class);
    var failed = read(db, 3);
    assertThat(failed.checkpoint().phase()).isEqualTo(RecordPublicationCheckpoint.Phase.STAGING);
    assertThat(failed.checkpoint().staged()).isZero();
    assertThat(failed.checkpoint().failureCode()).isNotNull();
    assertThat(failed.checkpoint().sequence()).isEqualTo(2);
    entry = coordinator.advance(ACTOR, failing, true);
    for (int attempt = 0; entry.checkpoint().progress() != null && attempt < 4; attempt++) entry = coordinator.advance(ACTOR, failing, true);
    assertThat(entry.checkpoint().phase()).isEqualTo(RecordPublicationCheckpoint.Phase.CANCELLED);
    assertThat(installed(db, 3)).isEqualTo(installed);
    try (var connection = transaction(db)) { assertThat(snapshot(db, connection)).isEqualTo(beforeReplacement); }
    // Old terminal identity still resolves after subsequent versions and resource generations changed.
    assertThat(coordinator.advance(ACTOR, command, false).intent()).isEqualTo(originalIntent);
    RecordCalculationPublicServiceAssertions.verify(db, resolver, coordinator);
  }

  private static RecordWritePolicyPublicationCommand command(RecordWritePolicyPublicationCommand base, long expected, long target, String formula) {
    var formulas = new LinkedHashMap<>(base.calculation().formulas()); formulas.put("amount", formula);
    return new RecordWritePolicyPublicationCommand(base.physicalModelId(), base.appId(), base.formId(), base.schemaReference(), expected, target,
        "publication-" + target, base.policyChecksum(), Map.of(), base.timeoutMs(), Map.of(),
        new RecordCalculationDefinition("1", formulas, base.calculation().details()));
  }

  private static RecordPublicationIntent freeze(RecordPublicationPatchFixture fixture, RecordWritePolicyPublicationCommand command,
      RecordPublicationMetadata.Snapshot snapshot) {
    return new RecordPublicationIntent(2, fixture.intent.key(), command, fixture.intent.fingerprint(), fixture.intent.previousFingerprint(),
        fixture.intent.actorId(), fixture.intent.evaluatedAt(), snapshot.bindings(), null, snapshot.resources());
  }

  private static void legacy(ControlFixture control, String model, String resource, String tenant, boolean deleted) {
    var binding = new PhysicalModelResourceBindingEntity(); binding.setId(UUID.randomUUID().toString()); binding.setPhysicalModelId(model);
    binding.setTenantId(tenant); binding.setResourceCode(resource); binding.setCreatedBy("seed"); binding.setUpdatedBy("seed");
    if (deleted) binding.setDeletedAt(Instant.now());
    control.em().persist(binding);
  }

  private static PhysicalResourceProjectionEntity projection(ControlFixture control, String resource) {
    return control.em().createQuery("from PhysicalResourceProjectionEntity p where p.tenantId = :tenant and p.resourceCode = :resource",
        PhysicalResourceProjectionEntity.class).setParameter("tenant", ACTOR.tenantId()).setParameter("resource", resource).getSingleResult();
  }

  private static long version(ControlFixture control, RuntimeRecordModel model) {
    return control.transactions().execute(status -> control.em().find(PhysicalModelEntity.class, model.physicalModelId()).getLockVersion());
  }

  private static RecordPublicationStore.Entry read(Database db, long version) {
    var store = new RecordPublicationStore();
    return db.backend().publication(db.fixture().parent, List.of(db.fixture().child), db.fixture().intent.key(), 30_000,
        s -> store.read(db.fixture().intent.key(), version, s.read(store.lookup(db.fixture().intent.key(), version))));
  }

  private static RecordCalculationStore.Installed installed(Database db, long version) {
    var store = new RecordCalculationStore();
    return db.backend().publication(db.fixture().parent, List.of(db.fixture().child), db.fixture().intent.key(), 30_000,
        s -> store.read(db.fixture().intent.key(), version, s.read(store.lookup(db.fixture().intent.key(), version))));
  }
}
