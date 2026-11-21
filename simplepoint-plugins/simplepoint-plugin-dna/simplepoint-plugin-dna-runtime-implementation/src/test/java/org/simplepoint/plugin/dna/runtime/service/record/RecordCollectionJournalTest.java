package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionEdgeMutation;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

class RecordCollectionJournalTest {
  private final RecordCollectionJournal journal = new RecordCollectionJournal();
  private final RuntimeActor actor = new RuntimeActor("tenant", "actor");
  private final RecordRequestFingerprint fingerprints =
      new RecordRequestFingerprint(new ObjectMapper());
  private final CollectionRecordCommand command = command("a");
  private final RecordOperationIdentity operation =
      RecordOperationIdentity.of(actor, command, fingerprints.of(command));
  private final RecordCollectionBinding binding =
      RecordCollectionBindingTest.binding("projects", "members", "edges", "source_id", "target_id");
  private final CollectionRecordResult result =
      new CollectionRecordResult(
          "1",
          id(1),
          3L,
          List.of(new CollectionRecordResult.Edge(id(2), 7L, 4L, true, false)),
          Instant.EPOCH);

  @Test
  void persistsAndReplaysCompleteNoOpResultsWithoutRequiringCurrentTargetMetadata() {
    var insert = journal.insert(operation, command, binding, result);
    String json = (String) insert.parameters().getLast();
    assertThat(journal.read(operation, command, query(json))).isEqualTo(result);
    assertThat(
            journal.read(
                operation,
                command,
                new CalciteQueryResult(List.of(), List.of(), false, 0, 0, null)))
        .isNull();
    assertThat(journal.lookup(operation).parameters()).containsExactly(operation.id());
    assertThatThrownBy(() -> new RecordOperationJournal().read(operation, query(json)))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void changedSchemaOrActorCannotReuseTheOriginalReceipt() {
    String json =
        (String) journal.insert(operation, command, binding, result).parameters().getLast();
    var changed = command("b");
    var changedIdentity = RecordOperationIdentity.of(actor, changed, fingerprints.of(changed));
    assertThat(changedIdentity.id()).isEqualTo(operation.id());
    conflict(() -> journal.read(changedIdentity, changed, query(json)));
    var otherActor =
        RecordOperationIdentity.of(
            new RuntimeActor("tenant", "other"), command, fingerprints.of(command));
    conflict(() -> journal.read(otherActor, command, query(json)));
  }

  @Test
  void durableNegativeReceiptsCannotTurnIntoSuccessfulRetries() {
    for (var code :
        List.of(
            DnaRuntimeErrorCode.RECORD_OPERATION_REJECTED,
            DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT)) {
      String json = (String) journal.reject(operation, Instant.EPOCH, code).parameters().getLast();
      assertThatThrownBy(() -> journal.read(operation, command, query(json)))
          .isInstanceOfSatisfying(
              DnaRuntimeException.class,
              failure -> assertThat(failure.errorCode()).isEqualTo(code));
    }
    for (String json :
        List.of(
            "{\"version\":2,\"outcome\":\"REJECTED\",\"code\":\"RECORD_ACCESS_DENIED\"}",
            "{\"version\":1,\"outcome\":\"REJECTED\",\"extra\":true}",
            "[]")) {
      assertThatThrownBy(() -> journal.read(operation, command, query(json)))
          .isInstanceOfSatisfying(
              DnaRuntimeException.class,
              failure ->
                  assertThat(failure.errorCode())
                      .isEqualTo(DnaRuntimeErrorCode.PERSISTENCE_FAILED));
    }
  }

  private CalciteQueryResult query(String json) {
    return new CalciteQueryResult(
        List.of(),
        List.of(
            List.of(
                operation.tenantId(),
                operation.actorId(),
                operation.modelId(),
                operation.key(),
                operation.fingerprint(),
                json)),
        false,
        1,
        0,
        null);
  }

  private static CollectionRecordCommand command(String checksum) {
    return new CollectionRecordCommand(
        "1",
        "projects",
        new RecordSchemaReference(1, checksum.repeat(64)),
        "members",
        id(1),
        3L,
        List.of(new CollectionEdgeMutation(id(2), 7L, 4L, CollectionEdgeMutation.Mode.LINK)),
        "collection-noop",
        30_000);
  }

  private static String id(int number) {
    return new UUID(0, number).toString();
  }

  private static void conflict(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            DnaRuntimeException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(DnaRuntimeErrorCode.RECORD_IDEMPOTENCY_CONFLICT));
  }
}
