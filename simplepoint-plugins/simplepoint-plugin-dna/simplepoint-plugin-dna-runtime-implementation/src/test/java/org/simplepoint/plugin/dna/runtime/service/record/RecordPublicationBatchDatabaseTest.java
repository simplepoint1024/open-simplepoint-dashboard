package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationPatchDatabaseTest.snapshot;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationPatchDatabaseTest.transaction;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationPatchDatabaseTest.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationScope;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCompletenessQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordJsonSemantics;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueCondition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueConditionOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordViolationQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationPatchDatabaseTest.Database;

/** Real target state-machine execution. Control metadata resolution and APP scheduling are separate gates. */
class RecordPublicationBatchDatabaseTest {
  private static final String CANCEL = "c".repeat(64);
  private static final RecordPublicationStore JOURNAL = new RecordPublicationStore();
  private static final RecordPublicationStages STAGES = new RecordPublicationStages();
  private static final RecordCalculationStore CALCULATIONS = new RecordCalculationStore();

  @Test
  void postgresqlExecutesAndRecoversBatches() throws Exception {
    verify("POSTGRES", true);
  }

  @Test
  void mysqlExecutesAndRecoversBatches() throws Exception {
    verify("MYSQL", false);
  }

  private static void verify(String database, boolean postgres) throws Exception {
    RecordPublicationPatchDatabaseTest.verify(database, postgres, RecordPublicationBatchDatabaseTest::commit);
    for (int applied : List.of(-1, 0, 1, 2)) {
      RecordPublicationPatchDatabaseTest.verify(database, postgres, db -> cancel(db, applied));
    }
    RecordPublicationPatchDatabaseTest.verify(database, postgres, db -> empty(db, false));
    RecordPublicationPatchDatabaseTest.verify(database, postgres, db -> empty(db, true));
    RecordPublicationPatchDatabaseTest.verify(database, postgres, db -> sparse(db, false));
    RecordPublicationPatchDatabaseTest.verify(database, postgres, db -> sparse(db, true));
    RecordPublicationPatchDatabaseTest.verify(database, postgres, RecordPublicationBatchDatabaseTest::rejected);
  }

  private static void seed(Database db, int count) throws Exception {
    try (var connection = transaction(db)) {
      for (int index = 0; index < count; index++) {
        for (var plan : db.fixture().seed()) {
          int row = index;
          var parameters = plan.parameters().stream().map(value -> replaceId(value, row)).toList();
          var copy = new RecordSqlStatement(plan.sql(), parameters, plan.mutationType(),
              (String) replaceId(plan.recordId(), row), plan.resultVersion(), plan.updatedAt());
          assertThat(update(db, connection, copy)).isEqualTo(1);
        }
      }
      db.fence().initialize(connection, db.fixture().intent.key(), db.fixture().intent.actorId(), 30_000);
      connection.commit();
    }
  }

  private static Object replaceId(Object value, int index) {
    if (index == 0) return value;
    if (RecordPublicationPatchFixture.PARENT.equals(value)) return "00000000-0000-4000-8000-000000000021";
    if (RecordPublicationPatchFixture.CHILD.equals(value)) return "00000000-0000-4000-8000-000000000022";
    return value;
  }

  private static void commit(Database db) throws Exception {
    seed(db, 2);
    Map<String, List<Map<String, String>>> original;
    try (var connection = transaction(db)) { original = snapshot(db, connection); }
    var fixture = db.fixture();
    var executor = new RecordPublicationBatchExecutor(fixture.codec, fixture.sql);
    var entry = session(db, s -> executor.start(fixture.intent, fixture.models, s, CANCEL));
    assertThat(entry.checkpoint().total()).isEqualTo(2);
    assertThat(entry.checkpoint().sequence()).isEqualTo(1);
    assertThat((Object) session(db, s -> executor.start(fixture.intent, fixture.models, s, CANCEL))).isEqualTo(entry);
    blocked(db);
    // Staging payload/manifest/checkpoint writes must all roll back together.
    failAfterWrite(db, executor, entry, "simpoint_dna_publication_stages_v1");
    entry = step(db, executor, entry);
    var first = entry;
    var header = session(db, s -> STAGES.read(fixture.intent, RecordPublicationPatchFixture.PARENT,
        s.read(STAGES.lookup(fixture.intent, RecordPublicationPatchFixture.PARENT))));
    assertThat((Object) session(db, s -> STAGES.payload(fixture.intent, header, s::read))).isEqualTo(fixture.aggregate);
    // Replay of an earlier request must not process the next aggregate.
    assertThat((Object) session(db, s -> executor.step(fixture.intent, fixture.models, Map.of(), 1, 1, CANCEL, s))).isEqualTo(first);
    assertThatThrownBy(() -> session(db, s -> executor.step(fixture.intent, fixture.models, Map.of(), 99, 1, CANCEL, s)))
        .isInstanceOf(DnaRuntimeException.class);
    entry = step(db, executor, entry);
    entry = step(db, executor, entry);
    assertThat(entry.checkpoint().phase()).isEqualTo(RecordPublicationCheckpoint.Phase.APPLYING);
    failAfterWrite(db, executor, entry, "patch_parents");
    failAfterWrite(db, executor, entry, "patch_children");
    failAfterWrite(db, executor, entry, "simpoint_dna_publication_stages_v1");
    failAfterWrite(db, executor, entry, "simpoint_dna_record_publications_v1");
    entry = step(db, executor, entry);
    entry = step(db, executor, entry);
    assertThat(entry.checkpoint().applied()).isEqualTo(2);
    blocked(db);
    assertThat(installed(db)).isNull();
    failAfterWrite(db, executor, entry, "simpoint_dna_record_calculations_v1");
    var beforeActivation = entry;
    assertThatThrownBy(() -> session(db, s -> {
      var fault = spy(s);
      doAnswer(invocation -> { invocation.callRealMethod(); throw new InjectedFailure(); }).when(fault).activate(any());
      return executor.step(fixture.intent, fixture.models, Map.of(), beforeActivation.checkpoint().sequence(), 1, CANCEL, fault);
    })).isInstanceOf(DnaRuntimeException.class).hasRootCauseInstanceOf(InjectedFailure.class);
    assertThat(read(db)).isEqualTo(entry);
    assertThat(installed(db)).isNull();
    blocked(db);
    entry = step(db, executor, entry);
    assertThat(entry.checkpoint().phase()).isEqualTo(RecordPublicationCheckpoint.Phase.COMMITTED);
    assertThat(installed(db).revision()).isEqualTo(1);
    assertThat(installed(db).policy().definition()).isEqualTo(fixture.intent.command().calculation());
    assertThat(session(db, RecordPublicationSession::admission).revision()).isEqualTo(1);
    db.backend().requireMaintenanceAvailable(fixture.parent, fixture.intent.key().tenantId());
    try (var connection = transaction(db)) {
      var actual = snapshot(db, connection);
      RecordPublicationPatchDatabaseTest.verifyCandidate(actual, original);
      assertThat(actual.get("parent")).hasSize(2).allSatisfy(row -> assertThat(row).containsEntry("record_version", "1"));
      assertThat(actual.get("child")).hasSize(2).allSatisfy(row -> assertThat(row).containsEntry("line_amount", "20.02"));
    }
    var terminal = entry;
    assertThat(step(db, executor, terminal)).isEqualTo(terminal);
    assertThat((Object) session(db, s -> executor.requestCancellation(fixture.intent, CANCEL, s))).isEqualTo(terminal);
    assertThat((Object) session(db, s -> executor.failed(fixture.intent, beforeActivation.checkpoint().sequence(),
        DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED, CANCEL, s))).isEqualTo(terminal);
  }

  private static void cancel(Database db, int applyCount) throws Exception {
    seed(db, 2);
    Map<String, List<Map<String, String>>> original;
    try (var connection = transaction(db)) { original = snapshot(db, connection); }
    var fixture = db.fixture();
    var executor = new RecordPublicationBatchExecutor(fixture.codec, fixture.sql);
    var entry = session(db, s -> executor.start(fixture.intent, fixture.models, s, CANCEL));
    entry = step(db, executor, entry);
    if (applyCount >= 0) {
      entry = step(db, executor, entry);
      entry = step(db, executor, entry);
      for (int index = 0; index < applyCount; index++) entry = step(db, executor, entry);
    }
    entry = session(db, s -> executor.requestCancellation(fixture.intent, CANCEL, s));
    assertThat(entry.checkpoint().phase()).isEqualTo(RecordPublicationCheckpoint.Phase.RESTORING);
    assertThat((Object) session(db, s -> executor.requestCancellation(fixture.intent, CANCEL, s))).isEqualTo(entry);
    if (applyCount > 0) failAfterWrite(db, executor, entry, "patch_children");
    for (int attempts = 0; entry.checkpoint().progress() != null && attempts < 5; attempts++) {
      blocked(db);
      entry = step(db, executor, entry);
    }
    assertThat(entry.checkpoint().phase()).isEqualTo(RecordPublicationCheckpoint.Phase.CANCELLED);
    try (var connection = transaction(db)) { assertThat(snapshot(db, connection)).isEqualTo(original); }
    assertThat(installed(db)).isNull();
    assertThat(session(db, RecordPublicationSession::admission).revision()).isEqualTo(1);
    assertThat(session(db, RecordPublicationSession::admission).fingerprint()).isEqualTo(CANCEL);
    assertThat(step(db, executor, entry)).isEqualTo(entry);
    db.backend().requireMaintenanceAvailable(fixture.parent, fixture.intent.key().tenantId());
  }

  private static void empty(Database db, boolean cancel) throws Exception {
    seed(db, 0);
    var fixture = db.fixture();
    var executor = new RecordPublicationBatchExecutor(fixture.codec, fixture.sql);
    var entry = session(db, s -> executor.start(fixture.intent, fixture.models, s, CANCEL));
    assertThat(entry.checkpoint().total()).isZero();
    if (cancel) entry = session(db, s -> executor.requestCancellation(fixture.intent, CANCEL, s));
    else entry = step(db, executor, entry);
    blocked(db);
    entry = step(db, executor, entry);
    assertThat(entry.checkpoint().phase()).isEqualTo(cancel ? RecordPublicationCheckpoint.Phase.CANCELLED : RecordPublicationCheckpoint.Phase.COMMITTED);
    assertThat(installed(db) == null).isEqualTo(cancel);
    db.backend().requireMaintenanceAvailable(fixture.parent, fixture.intent.key().tenantId());
  }

  private static void sparse(Database db, boolean childOnly) throws Exception {
    seed(db, 1);
    var fixture = db.fixture();
    var definition = new RecordCalculationDefinition("1", Map.of(), Map.of("lines", new RecordCalculationScope(
        fixture.child.physicalModelId(), fixture.reference(fixture.child, false), childOnly ? Map.of("amount", "20.02") : Map.of())));
    var intent = intent(fixture, definition, Map.of(), Map.of());
    var executor = new RecordPublicationBatchExecutor(fixture.codec, fixture.sql);
    Map<String, List<Map<String, String>>> original;
    try (var connection = transaction(db)) { original = snapshot(db, connection); }
    var entry = session(db, s -> executor.start(intent, fixture.models, s, CANCEL));
    for (int attempt = 0; entry.checkpoint().progress() != null && attempt < 5; attempt++) {
      long sequence = entry.checkpoint().sequence();
      entry = session(db, s -> executor.step(intent, fixture.models, Map.of(), sequence, 1, CANCEL, s));
    }
    assertThat(entry.checkpoint().phase()).isEqualTo(RecordPublicationCheckpoint.Phase.COMMITTED);
    try (var connection = transaction(db)) {
      var actual = snapshot(db, connection);
      if (childOnly) {
        var parent = new LinkedHashMap<>(original.get("parent").getFirst());
        var child = new LinkedHashMap<>(original.get("child").getFirst());
        for (var row : List.of(parent, child)) {
          row.put("record_version", "1");
          row.put("updated_by", "publisher");
          row.put("updated_at", actual.get("parent").getFirst().get("updated_at"));
        }
        child.put("line_amount", "20.02");
        assertThat(actual).isEqualTo(Map.of("parent", List.of(parent), "child", List.of(child)));
      } else {
        assertThat(actual).isEqualTo(original);
      }
    }
    assertThat(installed(db).policy().definition()).isEqualTo(definition);
  }

  private static void rejected(Database db) throws Exception {
    seed(db, 1);
    var fixture = db.fixture();
    var formulas = new LinkedHashMap<>(fixture.intent.command().calculation().formulas());
    formulas.put("text", "\"\"");
    var definition = new RecordCalculationDefinition("1", formulas, fixture.intent.command().calculation().details());
    var reference = fixture.reference(fixture.parent, false);
    var completeness = new RecordCompletenessQuery(fixture.parent.physicalModelId(), "patch-app", "parent-form", reference,
        List.of("old_summary"), null);
    var condition = new RecordValueCondition(RecordValueConditionOperator.LESS_THAN, "old_units",
        List.of(new LogicalValue(LogicalValueKind.INTEGER, "0")), List.of());
    var violation = new RecordViolationQuery(fixture.parent.physicalModelId(), "patch-app", "parent-form", reference,
        null, condition, RecordJsonSemantics.STRUCTURAL_V1);
    var intent = intent(fixture, definition, Map.of("required", completeness), Map.of("positive", violation));
    var history = Map.of(reference, fixture.parent);
    var executor = new RecordPublicationBatchExecutor(fixture.codec, fixture.sql);
    Map<String, List<Map<String, String>>> original;
    try (var connection = transaction(db)) { original = snapshot(db, connection); }
    var entry = session(db, s -> executor.start(intent, fixture.models, s, CANCEL));
    for (int attempt = 0; attempt < 3; attempt++) {
      long sequence = entry.checkpoint().sequence();
      entry = session(db, s -> executor.step(intent, fixture.models, history, sequence, 1, CANCEL, s));
    }
    assertThat(entry.checkpoint().applied()).isEqualTo(1);
    long sequence = entry.checkpoint().sequence();
    // Missing trusted historical schema must not be mistaken for an empty check set.
    assertThatThrownBy(() -> session(db, s -> executor.step(intent, fixture.models, Map.of(), sequence, 1, CANCEL, s)))
        .isInstanceOfSatisfying(DnaRuntimeException.class, e -> assertThat(e.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE));
    assertThatThrownBy(() -> session(db, s -> executor.step(intent, fixture.models, history, sequence, 1, CANCEL, s)))
        .isInstanceOfSatisfying(RecordPublicationChecks.Rejected.class, e -> {
          assertThat(e.violations().get("required").emptyCounts()).containsEntry("old_summary", 1L);
          assertThat(e.valueViolations()).containsEntry("positive", 1L);
        });
    assertThat(read(db).checkpoint()).isEqualTo(entry.checkpoint());
    assertThat(installed(db)).isNull();
    blocked(db);
    entry = session(db, s -> executor.failed(intent, sequence, DnaRuntimeErrorCode.RECORD_WRITE_POLICY_PRECHECK_FAILED, CANCEL, s));
    assertThat(entry.checkpoint().failureCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_WRITE_POLICY_PRECHECK_FAILED);
    assertThat((Object) session(db, s -> executor.failed(intent, sequence, DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED, CANCEL, s))).isEqualTo(entry);
    entry = session(db, s -> executor.requestCancellation(intent, CANCEL, s));
    for (int attempt = 0; entry.checkpoint().progress() != null && attempt < 3; attempt++) {
      long current = entry.checkpoint().sequence();
      entry = session(db, s -> executor.step(intent, fixture.models, history, current, 1, CANCEL, s));
    }
    assertThat(entry.checkpoint().phase()).isEqualTo(RecordPublicationCheckpoint.Phase.CANCELLED);
    try (var connection = transaction(db)) { assertThat(snapshot(db, connection)).isEqualTo(original); }
    assertThat(installed(db)).isNull();
  }

  private static RecordPublicationIntent intent(RecordPublicationPatchFixture fixture, RecordCalculationDefinition definition,
      Map<String, RecordCompletenessQuery> checks, Map<String, RecordViolationQuery> values) {
    var old = fixture.intent.command();
    var command = new RecordWritePolicyPublicationCommand(old.physicalModelId(), old.appId(), old.formId(), old.schemaReference(),
        old.expectedVersion(), old.targetVersion(), old.publicationKey(), old.policyChecksum(), checks, old.timeoutMs(), values, definition);
    return new RecordPublicationIntent(1, fixture.intent.key(), command, new RecordRequestFingerprint(new ObjectMapper()).of(command),
        fixture.intent.previousFingerprint(), fixture.intent.actorId(), fixture.intent.evaluatedAt(), fixture.intent.bindings(), null);
  }

  private static void failAfterWrite(Database db, RecordPublicationBatchExecutor executor, RecordPublicationStore.Entry entry, String table) throws Exception {
    Map<String, List<Map<String, String>>> before;
    try (var connection = transaction(db)) { before = snapshot(db, connection); }
    assertThatThrownBy(() -> session(db, s -> {
      var fault = spy(s);
      doAnswer(invocation -> {
        invocation.callRealMethod();
        RecordSqlStatement statement = invocation.getArgument(0);
        if (statement.sql().contains('"' + table + '"')) throw new InjectedFailure();
        return null;
      }).when(fault).write(any());
      return executor.step(db.fixture().intent, db.fixture().models, Map.of(), entry.checkpoint().sequence(), 1, CANCEL, fault);
    })).as("fault after write to %s", table).isInstanceOf(DnaRuntimeException.class).hasRootCauseInstanceOf(InjectedFailure.class);
    assertThat(read(db)).isEqualTo(entry);
    try (var connection = transaction(db)) { assertThat(snapshot(db, connection)).isEqualTo(before); }
    blocked(db);
  }

  private static RecordPublicationStore.Entry step(Database db, RecordPublicationBatchExecutor executor, RecordPublicationStore.Entry entry) {
    return session(db, s -> executor.step(db.fixture().intent, db.fixture().models, Map.of(), entry.checkpoint().sequence(), 1, CANCEL, s));
  }

  private static RecordPublicationStore.Entry read(Database db) {
    return session(db, s -> JOURNAL.read(db.fixture().intent.key(), 1, s.read(JOURNAL.lookup(db.fixture().intent.key(), 1))));
  }

  private static RecordCalculationStore.Installed installed(Database db) {
    return session(db, s -> CALCULATIONS.read(db.fixture().intent.key(), 1, s.read(CALCULATIONS.lookup(db.fixture().intent.key(), 1))));
  }

  private static <T> T session(Database db, Function<RecordPublicationSession, T> work) {
    return db.backend().publication(db.fixture().parent, List.of(db.fixture().child), db.fixture().intent.key(), 30_000, work);
  }

  private static void blocked(Database db) {
    assertThatThrownBy(() -> db.backend().requireMaintenanceAvailable(db.fixture().parent, db.fixture().intent.key().tenantId()))
        .isInstanceOfSatisfying(DnaRuntimeException.class, error -> assertThat(error.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_MAINTENANCE_IN_PROGRESS));
  }

  private static final class InjectedFailure extends RuntimeException {}
}
