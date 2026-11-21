package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationPatchDatabaseTest.transaction;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationPatchDatabaseTest.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationScope;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCompletenessQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilter;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationPatchDatabaseTest.Database;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

/**
 * Native durable publication/inspection coverage; old protected values are privileged test
 * fixtures.
 */
class RecordQueryProtectionDatabaseTest {
  private static final String CANCEL = "c".repeat(64);
  private static final RecordCalculationStore STORE = new RecordCalculationStore();

  @Test
  void postgresqlRetainsProtectionThroughPublicationRemovalReplayAndOwnedInspection()
      throws Exception {
    RecordPublicationPatchDatabaseTest.verify("POSTGRES", true, db -> verify(db, false));
  }

  @Test
  void mysqlRetainsProtectionThroughPublicationRemovalReplayAndOwnedInspection() throws Exception {
    RecordPublicationPatchDatabaseTest.verify("MYSQL", false, db -> verify(db, false));
  }

  @Test
  void postgresqlPublicationRestoresCopiedAndNewDerivedOriginsAfterRollback() throws Exception {
    RecordPublicationPatchDatabaseTest.verify("POSTGRES", true, db -> verify(db, true));
  }

  @Test
  void mysqlPublicationRestoresCopiedAndNewDerivedOriginsAfterRollback() throws Exception {
    RecordPublicationPatchDatabaseTest.verify("MYSQL", false, db -> verify(db, true));
  }

  private static void verify(final Database db, boolean restore) throws Exception {
    var fixture = db.fixture();
    var key = fixture.intent.key();
    try (var connection = transaction(db)) {
      for (var statement : fixture.seed()) {
        assertThat(update(db, connection, statement)).isEqualTo(1);
      }
      var original = RecordReferenceCopyStoreTest.origin();
      var source =
          new org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceCopyOrigin(
              original.relationId(),
              original.mappingId(),
              original.mode(),
              "source",
              original.mappingRevision(),
              original.targetModelId(),
              original.targetAppId(),
              original.targetFormId(),
              original.targetResourceCode(),
              original.targetRecordId(),
              original.targetVersion(),
              original.targetFieldId(),
              original.requirements());
      assertThat(
              update(
                  db,
                  connection,
                  new RecordReferenceCopyStore()
                      .write(
                          new RecordReferenceCopyStore.Key(
                              key.tenantId(), key.modelId(), RecordPublicationPatchFixture.PARENT),
                          "fixture",
                          null,
                          0,
                          List.of(source),
                          RecordPublicationPatchFixture.BEFORE)))
          .isEqualTo(1);
      db.fence().initialize(connection, key, "fixture", 30000);
      connection.commit();
    }
    // A trusted pre-existing publication with retained copied data; this does not open mapping
    // admission.
    var initial =
        fixture.intent.withQueryProtection(
            Map.of(
                key.modelId(), Set.of("source"), fixture.child.physicalModelId(), Set.of("memo")));
    session(
        db,
        session -> {
          session.write(
              STORE.insert(
                  key,
                  1,
                  "fixture",
                  RecordPublicationPatchFixture.AT,
                  new RecordCalculationPolicy(
                      1,
                      initial.command().appId(),
                      initial.command().formId(),
                      initial.command().schemaReference(),
                      initial.command().calculation(),
                      initial.queryProtection())));
          session.activate(initial);
          return null;
        });
    var previous = installed(db, 1);
    var definition =
        new RecordCalculationDefinition(
            "1",
            Map.of("amount", "LEN([source])"),
            Map.of(
                "renamed",
                new RecordCalculationScope(
                    fixture.child.physicalModelId(),
                    fixture.reference(fixture.child, false),
                    Map.of("amount", "LEN([memo])+[parent.amount]"))));
    var executor = new RecordPublicationBatchExecutor(fixture.codec, fixture.sql);
    var second =
        executor.protect(
            intent(fixture, definition, 1, initial.fingerprint(), previous),
            fixture.models,
            previous);
    assertThat(second.queryProtection().get(key.modelId())).containsExactly("amount", "source");
    assertThat(second.queryProtection().get(fixture.child.physicalModelId()))
        .containsExactly("amount", "memo");
    if (restore) {
      verifyRestoration(db, executor, second, initial);
      return;
    }
    complete(db, executor, second);
    var parentProof = copies(db, key.modelId(), RecordPublicationPatchFixture.PARENT);
    var childProof =
        copies(db, fixture.child.physicalModelId(), RecordPublicationPatchFixture.CHILD);
    assertThat(parentProof.sourceVersion()).isEqualTo(1);
    assertThat(parentProof.origins()).hasSize(1);
    assertThat(parentProof.derivedOrigins())
        .singleElement()
        .satisfies(origin -> assertThat(origin.destinationFieldId()).isEqualTo("amount"));
    assertThat(childProof.sourceVersion()).isEqualTo(1);
    assertThat(childProof.derivedOrigins())
        .singleElement()
        .satisfies(
            origin -> {
              assertThat(origin.destinationFieldId()).isEqualTo("amount");
              assertThat(origin.requirements())
                  .containsExactlyElementsOf(
                      parentProof.derivedOrigins().getFirst().requirements());
            });
    assertThat(installed(db, 2).policy().queryProtection()).isEqualTo(second.queryProtection());
    verifyReads(db, second);
    var equalDefinition =
        new RecordCalculationDefinition(
            "1", Map.of("amount", "LEN([source])+0"), definition.details());
    var equal =
        executor.protect(
            intent(fixture, equalDefinition, 2, second.fingerprint(), installed(db, 2)),
            fixture.models,
            installed(db, 2));
    complete(db, executor, equal);
    var equalParent = copies(db, key.modelId(), RecordPublicationPatchFixture.PARENT);
    var equalChild =
        copies(db, fixture.child.physicalModelId(), RecordPublicationPatchFixture.CHILD);
    assertThat(equalParent.sourceVersion()).isEqualTo(2);
    assertThat(equalChild.sourceVersion()).isEqualTo(2);
    assertThat(equalParent.derivedOrigins().getFirst().calculationRevision()).isEqualTo(3);
    assertThat(equalParent.derivedOrigins().getFirst().requirements())
        .isEqualTo(parentProof.derivedOrigins().getFirst().requirements());
    var empty =
        new RecordCalculationDefinition(
            "1",
            Map.of(),
            Map.of(
                "details",
                new RecordCalculationScope(
                    fixture.child.physicalModelId(),
                    fixture.reference(fixture.child, false),
                    Map.of())));
    var current = installed(db, 3);
    var third =
        executor.protect(
            intent(fixture, empty, 3, equal.fingerprint(), current), fixture.models, current);
    complete(db, executor, third);
    assertThat(copies(db, key.modelId(), RecordPublicationPatchFixture.PARENT))
        .isEqualTo(equalParent);
    assertThat(copies(db, fixture.child.physicalModelId(), RecordPublicationPatchFixture.CHILD))
        .isEqualTo(equalChild);
    assertThat(installed(db, 4).policy().queryProtection()).isEqualTo(second.queryProtection());
    verifyReads(db, third);
    // Old terminal replay compares its frozen protection, independently of later installed
    // policies.
    assertThat(
            session(db, session -> executor.start(second, fixture.models, session, CANCEL))
                .intent())
        .isEqualTo(second);
  }

  private static void verifyRestoration(
      Database db,
      RecordPublicationBatchExecutor executor,
      RecordPublicationIntent intent,
      RecordPublicationIntent previous)
      throws Exception {
    var fixture = db.fixture();
    final var before = copies(db, intent.key().modelId(), RecordPublicationPatchFixture.PARENT);
    Map<String, List<Map<String, String>>> values;
    try (var connection = transaction(db)) {
      values = RecordPublicationPatchDatabaseTest.snapshot(db, connection);
    }
    var entry = session(db, session -> executor.start(intent, fixture.models, session, CANCEL));
    for (int attempt = 0;
        entry.checkpoint().phase() == RecordPublicationCheckpoint.Phase.STAGING && attempt < 6;
        attempt++) {
      long sequence = entry.checkpoint().sequence();
      entry =
          session(
              db,
              session ->
                  executor.step(intent, fixture.models, Map.of(), sequence, 10, CANCEL, session));
    }
    assertThat(entry.checkpoint().phase()).isEqualTo(RecordPublicationCheckpoint.Phase.APPLYING);
    long applySequence = entry.checkpoint().sequence();
    assertThatThrownBy(
            () ->
                session(
                    db,
                    session -> {
                      executor.step(
                          intent, fixture.models, Map.of(), applySequence, 10, CANCEL, session);
                      throw new IllegalStateException(
                          "Injected failure after business and origin application");
                    }))
        .isInstanceOf(DnaRuntimeException.class);
    assertThat(copies(db, intent.key().modelId(), RecordPublicationPatchFixture.PARENT))
        .isEqualTo(before);
    assertThat(copies(db, fixture.child.physicalModelId(), RecordPublicationPatchFixture.CHILD))
        .isNull();
    session(
        db,
        session ->
            executor.step(intent, fixture.models, Map.of(), applySequence, 10, CANCEL, session));
    assertThat(copies(db, fixture.child.physicalModelId(), RecordPublicationPatchFixture.CHILD))
        .isNotNull();
    entry = session(db, session -> executor.requestCancellation(intent, CANCEL, session));
    for (int attempt = 0; entry.checkpoint().progress() != null && attempt < 6; attempt++) {
      long sequence = entry.checkpoint().sequence();
      entry =
          session(
              db,
              session ->
                  executor.step(intent, fixture.models, Map.of(), sequence, 10, CANCEL, session));
    }
    assertThat(entry.checkpoint().phase()).isEqualTo(RecordPublicationCheckpoint.Phase.CANCELLED);
    assertThat(copies(db, intent.key().modelId(), RecordPublicationPatchFixture.PARENT))
        .isEqualTo(before);
    assertThat(copies(db, fixture.child.physicalModelId(), RecordPublicationPatchFixture.CHILD))
        .isNull();
    assertThat(installed(db, 2).policy().queryProtection()).isEqualTo(previous.queryProtection());
    try (var connection = transaction(db)) {
      assertThat(RecordPublicationPatchDatabaseTest.snapshot(db, connection)).isEqualTo(values);
    }
  }

  private static RecordReferenceCopyStore.Stored copies(Database db, String model, String record) {
    var key = new RecordReferenceCopyStore.Key(db.fixture().intent.key().tenantId(), model, record);
    var store = new RecordReferenceCopyStore();
    return session(db, session -> store.read(key, session.read(store.lookup(key))));
  }

  private static void verifyReads(final Database db, final RecordPublicationIntent intent) {
    var fixture = db.fixture();
    for (var model : List.of(fixture.parent, fixture.child)) {
      String stable = model == fixture.parent ? "source" : "memo";
      String code =
          model.requestedSchema().fields().stream()
              .filter(field -> field.fieldId().equals(stable))
              .findFirst()
              .orElseThrow()
              .code();
      var access =
          new RecordAccessContext(
              new RuntimeActor(intent.key().tenantId(), "reader"),
              null,
              Set.copyOf(model.requestedFieldCodes()),
              Set.of(),
              Set.of(),
              null,
              null);
      var forbidden =
          new RecordCompletenessQuery(
              model.physicalModelId(),
              model.appId(),
              model.formId(),
              fixture.reference(model, false),
              List.of(code),
              null);
      assertThatThrownBy(
              () ->
                  db.backend()
                      .inspect(
                          model,
                          access,
                          30000,
                          authorized -> fixture.sql.completeness(model, authorized, forbidden)))
          .isInstanceOf(DnaRuntimeException.class);
      // An unrelated inspection still works; source model and stable field remain separate
      // identities.
      String ordinary = model == fixture.parent ? "old_summary" : "row_order";
      var allowed =
          new RecordCompletenessQuery(
              model.physicalModelId(),
              model.appId(),
              model.formId(),
              fixture.reference(model, false),
              List.of(ordinary),
              null);
      assertThat(
              db.backend()
                  .inspect(
                      model,
                      access,
                      30000,
                      authorized -> fixture.sql.completeness(model, authorized, allowed))
                  .rows())
          .hasSize(1);
    }
    var raw =
        new RecordAccessContext(
            new RuntimeActor(intent.key().tenantId(), "reader"),
            null,
            Set.of("id", "record_version", "old_source_value", "old_summary"),
            Set.of(),
            Set.of(),
            null,
            null);
    var rules = new RecordCalculationAccess.Rules(Map.of(), Map.of(), intent.queryProtection());
    db.backend()
        .read(
            fixture.parent,
            List.of(),
            raw,
            30000,
            session -> {
              assertThat(session.calculation().policy().queryProtection())
                  .isEqualTo(intent.queryProtection());
              var authorized = rules.apply(fixture.parent, raw);
              var query =
                  new RecordPageQuery(
                      fixture.parent.physicalModelId(),
                      fixture.reference(fixture.parent, false),
                      0,
                      50,
                      List.of("old_summary"),
                      null,
                      List.of());
              assertThat(
                      session
                          .query()
                          .apply(fixture.sql.page(fixture.parent, authorized, query))
                          .rows())
                  .hasSize(1);
              assertThatThrownBy(
                      () ->
                          fixture.sql.count(
                              fixture.parent,
                              authorized,
                              new RecordFilter(
                                  RecordFilterOperator.EQUALS,
                                  "old_source_value",
                                  List.of("secret"),
                                  List.of())))
                  .isInstanceOf(DnaRuntimeException.class);
              return null;
            });
  }

  private static RecordPublicationIntent intent(
      final RecordPublicationPatchFixture fixture,
      final RecordCalculationDefinition definition,
      final long expected,
      final String previousFingerprint,
      final RecordCalculationStore.Installed previous) {
    var base = fixture.intent.command();
    var command =
        new RecordWritePolicyPublicationCommand(
            base.physicalModelId(),
            base.appId(),
            base.formId(),
            base.schemaReference(),
            expected,
            expected + 1,
            "protection-" + expected,
            base.policyChecksum(),
            Map.of(),
            base.timeoutMs(),
            Map.of(),
            definition);
    return new RecordPublicationIntent(
        1,
        fixture.intent.key(),
        command,
        new RecordRequestFingerprint(new ObjectMapper()).of(command),
        previousFingerprint,
        "publisher",
        fixture.intent.evaluatedAt(),
        fixture.intent.bindings(),
        new RecordPublicationIntent.InstalledCalculation(
            previous.revision(), RecordCalculationStore.policyChecksum(previous.policy())));
  }

  private static void complete(
      final Database db,
      final RecordPublicationBatchExecutor executor,
      final RecordPublicationIntent intent) {
    var entry =
        session(db, session -> executor.start(intent, db.fixture().models, session, CANCEL));
    for (int attempt = 0; entry.checkpoint().progress() != null && attempt < 6; attempt++) {
      long sequence = entry.checkpoint().sequence();
      entry =
          session(
              db,
              session ->
                  executor.step(
                      intent, db.fixture().models, Map.of(), sequence, 10, CANCEL, session));
    }
    assertThat(entry.checkpoint().phase()).isEqualTo(RecordPublicationCheckpoint.Phase.COMMITTED);
  }

  private static RecordCalculationStore.Installed installed(
      final Database db, final long revision) {
    var key = db.fixture().intent.key();
    return session(
        db, session -> STORE.read(key, revision, session.read(STORE.lookup(key, revision))));
  }

  private static <T> T session(
      final Database db, final Function<RecordPublicationSession, T> action) {
    return db.backend()
        .publication(
            db.fixture().parent,
            List.of(db.fixture().child),
            db.fixture().intent.key(),
            30000,
            action);
  }
}
