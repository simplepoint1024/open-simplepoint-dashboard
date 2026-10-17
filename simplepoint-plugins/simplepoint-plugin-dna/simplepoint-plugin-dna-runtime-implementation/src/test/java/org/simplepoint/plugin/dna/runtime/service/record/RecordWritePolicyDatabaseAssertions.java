package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCompletenessQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilter;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordJsonSemantics;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueCondition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueConditionOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordViolationQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;
import org.simplepoint.plugin.dna.runtime.configuration.DnaRuntimeExecutionProperties;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordWriteFenceSql;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;

/** Runs the production publication service using the journal fixture's real Calcite/JDBC target. */
final class RecordWritePolicyDatabaseAssertions {

  private static final RecordSchemaReference SCHEMA = new RecordSchemaReference(1, "a".repeat(64));

  private RecordWritePolicyDatabaseAssertions() {
  }

  static void verify(final DataSource source, final RuntimeRecordModel model, final RuntimeCalciteRecordBackend backend,
      final RecordSqlCompiler compiler, final RecordAccessContext access, final RecordWriteFenceSql sql,
      final AtomicBoolean failCommit, final AtomicInteger opened) throws Exception {
    var context = mock(RuntimeRequestContext.class);
    var models = mock(RuntimeRecordModelResolver.class);
    when(context.requireActor()).thenReturn(access.actor());
    when(models.resolve(eq(access.actor()), eq(model.physicalModelId()), any())).thenReturn(model);
    var service = new RecordWritePolicyServiceImpl(context, models, compiler, backend,
        new RecordRequestFingerprint(new ObjectMapper().findAndRegisterModules()),
        new RecordAdmissionController(new DnaRuntimeExecutionProperties()), mock(RecordAuditSupport.class), mock(RecordExecutionObserver.class));
    var command = command(8, 9, 15_000);
    assertThatThrownBy(() -> service.publish(command)).isInstanceOfSatisfying(DnaRuntimeException.class,
        failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_ACCESS_DENIED));
    assertThatThrownBy(() -> service.cancel(command)).isInstanceOfSatisfying(DnaRuntimeException.class,
        failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_ACCESS_DENIED));
    var authorization = new AuthorizationContext();
    authorization.setIsAdministrator(true);
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, authorization);
    try {
      // A later failing batch must roll back the transition, even when the first batch is clean.
      var create = compiler.mutation(model, access, new RecordCreateMutation("blank", Map.of("title", " \u3000")), Instant.now());
      backend.execute(model, new RecordWriteAdmission("tenant-a", 8), List.of(create), 15_000);
      int before = opened.get();
      var blocked = service.publish(command);
      assertThat(opened.get() - before).isEqualTo(1); // All checks and the lock share one physical connection.
      assertThat(blocked.published()).isFalse();
      assertThat(blocked.version()).isEqualTo(8);
      assertThat(blocked.violations()).containsOnlyKeys("z-all");
      assertThat(blocked.violations().get("z-all").emptyCounts()).containsEntry("title", 1L);
      var repaired = compiler.mutation(model, access,
          new RecordUpdateMutation("repair", create.recordId(), 0, Map.of("title", "complete")), Instant.now());
      backend.execute(model, new RecordWriteAdmission("tenant-a", 8), List.of(repaired), 15_000);
      failCommit.set(true);
      assertThatThrownBy(() -> service.publish(command)).isInstanceOf(DnaRuntimeException.class);
      // The commit succeeded despite acknowledgement loss. Only the new version can now write.
      var later = compiler.mutation(model, access,
          new RecordUpdateMutation("later", create.recordId(), 1, Map.of("title", "")), Instant.now());
      assertThatThrownBy(() -> backend.execute(model, new RecordWriteAdmission("tenant-a", 8), List.of(later), 15_000))
          .isInstanceOfSatisfying(DnaRuntimeException.class, failure ->
              assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_WRITE_POLICY_CONFLICT));
      backend.execute(model, new RecordWriteAdmission("tenant-a", 9), List.of(later), 15_000);
      var replay = service.publish(command(8, 9, 20_000));
      assertThat(replay.published()).isTrue(); // Does not repeat a now-failing inspection.
      assertThat(replay.version()).isEqualTo(9);
      assertThat(replay.publicationFingerprint()).isEqualTo(blocked.publicationFingerprint());
      var changed = new RecordWritePolicyPublicationCommand("model-a", "app-a", "form-a", SCHEMA, 8, 9,
          "publish-9", "b".repeat(64), command.checks(), 15_000);
      assertThatThrownBy(() -> service.publish(changed)).isInstanceOfSatisfying(DnaRuntimeException.class,
          failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_WRITE_POLICY_CONFLICT));
      var repairAgain = compiler.mutation(model, access,
          new RecordUpdateMutation("repair-again", create.recordId(), 2, Map.of("title", "complete")), Instant.now());
      backend.execute(model, new RecordWriteAdmission("tenant-a", 9), List.of(repairAgain), 15_000);
      try (var writer = source.getConnection()) {
        writer.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        writer.setAutoCommit(false);
        new RecordWriteFence(sql).admitWrite(writer, new RecordWriteFenceKey("tenant-a", "model-a"), 9, 15_000);
        assertThatThrownBy(() -> service.publish(command(9, 10, 100))).isInstanceOf(DnaRuntimeException.class);
        writer.rollback();
      }
      assertThat(service.publish(command(9, 10, 15_000)).version()).isEqualTo(10);
      // Owner mismatch is refused before any physical connection is opened.
      before = opened.get();
      var wrongOwner = new RecordWritePolicyPublicationCommand("model-a", "other-app", "form-a", SCHEMA,
          10, 11, "wrong-owner", "a".repeat(64), Map.of(), 15_000);
      assertThatThrownBy(() -> service.publish(wrongOwner)).isInstanceOfSatisfying(DnaRuntimeException.class,
          failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_ACCESS_DENIED));
      assertThat(opened.get()).isEqualTo(before);
      var unknown = new RecordCompletenessQuery("model-a", "app-a", "form-a", SCHEMA, List.of("title"), null,
          new RecordValueCondition(RecordValueConditionOperator.IS_EMPTY, "unknown_field", List.of(), List.of()));
      var invalid = new RecordWritePolicyPublicationCommand("model-a", "app-a", "form-a", SCHEMA,
          10, 11, "invalid-condition", "a".repeat(64), Map.of("invalid", unknown), 15_000);
      assertThatThrownBy(() -> service.publish(invalid)).isInstanceOfSatisfying(DnaRuntimeException.class,
          failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_QUERY_INVALID));
      assertThat(opened.get()).isEqualTo(before);
      verifyValueChecks(service, model, backend, compiler, access, opened, failCommit);
      verifyCancellation(service, model, backend, compiler, access, opened, failCommit);
    } finally {
      RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    }
  }

  private static void verifyValueChecks(final RecordWritePolicyServiceImpl service, final RuntimeRecordModel model,
      final RuntimeCalciteRecordBackend backend, final RecordSqlCompiler compiler, final RecordAccessContext access,
      final AtomicInteger opened, final AtomicBoolean failCommit) {
    var create = compiler.mutation(model, access, new RecordCreateMutation("retired-choice", Map.of("title", "retired")), Instant.now());
    backend.execute(model, new RecordWriteAdmission("tenant-a", 10), List.of(create), 15_000);
    var filter = new RecordFilter(RecordFilterOperator.AND, null, List.of(), List.of(
        new RecordFilter(RecordFilterOperator.IN, "title", List.of("retired", "complete", " "), List.of()),
        new RecordFilter(RecordFilterOperator.IS_NOT_NULL, "title", List.of(), List.of())));
    var value = new RecordViolationQuery("model-a", "app-a", "form-a", SCHEMA, filter,
        new RecordValueCondition(RecordValueConditionOperator.NOT, null, List.of(), List.of(
            new RecordValueCondition(RecordValueConditionOperator.IN, "title",
                List.of(new LogicalValue(LogicalValueKind.STRING, "complete")), List.of()))), RecordJsonSemantics.STRUCTURAL_V1);
    var required = new RecordCompletenessQuery("model-a", "app-a", "form-a", SCHEMA, List.of("title"), filter);
    var command = new RecordWritePolicyPublicationCommand("model-a", "app-a", "form-a", SCHEMA, 10, 11,
        "publish-choices", "a".repeat(64), Map.of("required", required), 15_000, Map.of("choices", value));
    int before = opened.get();
    var blocked = service.publish(command);
    assertThat(opened.get() - before).isEqualTo(1);
    assertThat(blocked.published()).isFalse();
    assertThat(blocked.violations()).isEmpty(); // The invalid value is nonempty.
    assertThat(blocked.valueViolations()).containsExactlyEntriesOf(Map.of("choices", 1L));
    assertThat(blocked.version()).isEqualTo(10);
    var blank = compiler.mutation(model, access,
        new RecordUpdateMutation("blank-choice", create.recordId(), 0, Map.of("title", " ")), Instant.now());
    backend.execute(model, new RecordWriteAdmission("tenant-a", 10), List.of(blank), 15_000);
    var both = service.publish(command);
    assertThat(both.violations()).containsOnlyKeys("required");
    assertThat(both.valueViolations()).containsEntry("choices", 1L);
    var repaired = compiler.mutation(model, access,
        new RecordUpdateMutation("repaired-choice", create.recordId(), 1, Map.of("title", "complete")), Instant.now());
    backend.execute(model, new RecordWriteAdmission("tenant-a", 10), List.of(repaired), 15_000);
    failCommit.set(true);
    assertThatThrownBy(() -> service.publish(command)).isInstanceOf(DnaRuntimeException.class);
    var replay = service.publish(command);
    assertThat(replay.published()).isTrue();
    assertThat(replay.version()).isEqualTo(11);
    assertThat(replay.valueViolations()).isEmpty();
    assertThat(replay.publicationFingerprint()).isEqualTo(blocked.publicationFingerprint());
    var removedCheck = new RecordWritePolicyPublicationCommand("model-a", "app-a", "form-a", SCHEMA, 10, 11,
        "publish-choices", "a".repeat(64), command.checks(), 15_000);
    assertThatThrownBy(() -> service.publish(removedCheck)).isInstanceOfSatisfying(DnaRuntimeException.class,
        failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_WRITE_POLICY_CONFLICT));
  }

  private static void verifyCancellation(final RecordWritePolicyServiceImpl service, final RuntimeRecordModel model,
      final RuntimeCalciteRecordBackend backend, final RecordSqlCompiler compiler, final RecordAccessContext access,
      final AtomicInteger opened, final AtomicBoolean failCommit) {
    final var beforeRows = backend.query(model, compiler.count(model, access, null), 15_000).rows();
    final var original = command(11, 12, 15_000);
    var wrongOwner = new RecordWritePolicyPublicationCommand("model-a", "other-app", "form-a", SCHEMA,
        11, 12, "wrong-cancellation", "a".repeat(64), Map.of(), 15_000);
    int before = opened.get();
    assertThatThrownBy(() -> service.cancel(wrongOwner)).isInstanceOfSatisfying(DnaRuntimeException.class,
        failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_ACCESS_DENIED));
    assertThat(opened.get()).isEqualTo(before);
    failCommit.set(true);
    assertThatThrownBy(() -> service.cancel(original)).isInstanceOf(DnaRuntimeException.class);
    assertThat(opened.get() - before).isEqualTo(1);
    var cancelled = service.cancel(command(11, 12, 20_000));
    assertThat(cancelled.cancelled()).isTrue();
    assertThat(cancelled.version()).isEqualTo(12);
    assertThat(cancelled.targetFingerprint()).isNotEqualTo(cancelled.publicationFingerprint());
    assertThatThrownBy(() -> service.publish(original)).isInstanceOfSatisfying(DnaRuntimeException.class,
        failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_WRITE_POLICY_CONFLICT));
    var write = compiler.mutation(model, access, new RecordCreateMutation("after-cancellation", Map.of("title", "complete")), Instant.now());
    assertThatThrownBy(() -> backend.execute(model, new RecordWriteAdmission("tenant-a", 11), List.of(write), 15_000))
        .isInstanceOfSatisfying(DnaRuntimeException.class,
            failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_WRITE_POLICY_CONFLICT));
    assertThat(backend.query(model, compiler.count(model, access, null), 15_000).rows()).isEqualTo(beforeRows);
    backend.execute(model, new RecordWriteAdmission("tenant-a", 12), List.of(write), 15_000);
    var next = command(12, 13, 15_000);
    var published = service.publish(next);
    var resolved = service.cancel(next);
    assertThat(resolved.cancelled()).isFalse();
    assertThat(resolved.version()).isEqualTo(13);
    assertThat(resolved.publicationFingerprint()).isEqualTo(published.publicationFingerprint());
    assertThat(resolved.targetFingerprint()).isEqualTo(published.publicationFingerprint());
    assertThat(service.publish(next)).isEqualTo(published);
  }

  private static RecordWritePolicyPublicationCommand command(final long previous, final long next, final int timeout) {
    var onlyComplete = new RecordFilter(RecordFilterOperator.EQUALS, "title", List.of("complete"), List.of());
    return new RecordWritePolicyPublicationCommand("model-a", "app-a", "form-a", SCHEMA, previous, next,
        "publish-" + next, "a".repeat(64), Map.of(
            "a-filtered", new RecordCompletenessQuery("model-a", "app-a", "form-a", SCHEMA, List.of("title"), onlyComplete),
            "b-never", new RecordCompletenessQuery("model-a", "app-a", "form-a", SCHEMA, List.of("title"), null,
                new RecordValueCondition(RecordValueConditionOperator.FALSE, null, List.of(), List.of())),
            "z-all", new RecordCompletenessQuery("model-a", "app-a", "form-a", SCHEMA, List.of("title"), null,
                new RecordValueCondition(RecordValueConditionOperator.NOT_EQUALS, "title",
                    List.of(new LogicalValue(LogicalValueKind.STRING, "optional")), List.of()))), timeout);
  }
}
