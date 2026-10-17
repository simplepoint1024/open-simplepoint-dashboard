package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.datascopeannotation.DataScopeCondition;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

class RecordCreateCoordinatorTest {

  private static final RecordSchemaReference SCHEMA = new RecordSchemaReference(
      1,
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  );

  @Test
  void recoversWhenTheTargetCommitSucceededBeforeTheResponseFailed() {
    RecordTransactionServiceImpl transactions = mock(RecordTransactionServiceImpl.class);
    RecordCreateReceiptStore receipts = mock(RecordCreateReceiptStore.class);
    RecordRequestFingerprint fingerprints = mock(RecordRequestFingerprint.class);
    RecordCreateCommand command = new RecordCreateCommand(
        "model-a",
        SCHEMA,
        "request-1",
        Map.of("title", "First")
    );
    RuntimeActor actor = new RuntimeActor("tenant-a", "user-a");
    PreparedRecordTransaction prepared = new PreparedRecordTransaction(
        new RecordTransactionCommand(
            "model-a",
            SCHEMA,
            List.of(new RecordCreateMutation("command", command.values())),
            30_000
        ),
        mock(RuntimeRecordModel.class),
        new RecordAccessContext(
            actor,
            "app.forms.orders",
            Set.of("id"),
            Set.of("title"),
            Set.of(),
            new DataScopeCondition(
                "ALL",
                "created_org_dept_id",
                "created_by",
                "user-a",
                Set.of()
            ),
            "dept-a"
        )
    );
    String recordId = "f28512b0-9e64-5c9a-90e8-c77441ad2303";
    Instant operationAt = Instant.parse("2026-10-13T01:02:03.123456Z");
    when(transactions.prepare(any(RecordTransactionCommand.class))).thenReturn(prepared);
    when(fingerprints.of(command)).thenReturn("fingerprint-a");
    when(receipts.claim(actor, command, "fingerprint-a"))
        .thenReturn(new RecordCreateReceiptClaim(recordId, operationAt, null));
    when(transactions.hasCommittedCreate(prepared, recordId))
        .thenReturn(false, true);
    when(transactions.executePreparedCreate(prepared, recordId, operationAt))
        .thenThrow(new DnaRuntimeException(
            DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED,
            "Response was lost"
        ));
    when(receipts.complete(eq(actor), eq(command), eq("fingerprint-a"), any()))
        .thenAnswer(call -> call.getArgument(3));

    RecordCommandResult result = new RecordCreateCoordinator(
        transactions,
        receipts,
        fingerprints
    ).create(command);

    assertThat(result.recordId()).isEqualTo(recordId);
    assertThat(result.updatedAt()).isEqualTo(operationAt);
    verify(receipts).complete(eq(actor), eq(command), eq("fingerprint-a"), eq(result));
  }

  @Test
  void rejectsInvalidValuesBeforeReservingTheIdempotencyKey() {
    RecordTransactionServiceImpl transactions = mock(RecordTransactionServiceImpl.class);
    RecordCreateReceiptStore receipts = mock(RecordCreateReceiptStore.class);
    RecordRequestFingerprint fingerprints = mock(RecordRequestFingerprint.class);
    PreparedRecordTransaction prepared = mock(PreparedRecordTransaction.class);
    RecordCreateCommand command = new RecordCreateCommand(
        "model-a",
        SCHEMA,
        "request-1",
        Map.of("unknown", "value")
    );
    when(transactions.prepare(any(RecordTransactionCommand.class))).thenReturn(prepared);
    DnaRuntimeException invalid = new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_COMMAND_INVALID,
        "Unknown record field"
    );
    doThrow(invalid).when(transactions).validatePreparedCreate(prepared);

    assertThatThrownBy(() -> new RecordCreateCoordinator(
        transactions,
        receipts,
        fingerprints
    ).create(command)).isSameAs(invalid);

    verifyNoInteractions(receipts, fingerprints);
  }

  @Test
  void lookupRecoversCommittedPendingReceiptsButNeverCreatesRowsOrReservesKeys() {
    var transactions = mock(RecordTransactionServiceImpl.class);
    var receipts = mock(RecordCreateReceiptStore.class);
    var fingerprints = mock(RecordRequestFingerprint.class);
    var prepared = mock(PreparedRecordTransaction.class);
    var access = mock(RecordAccessContext.class);
    var actor = new RuntimeActor("tenant-a", "user-a");
    var command = new RecordCreateCommand("model-a", SCHEMA, "request-1", Map.of("title", "Original"));
    var result = new RecordCommandResult(RecordMutationType.CREATE, "record-a", 0, 1, Instant.EPOCH);
    when(transactions.prepare(any())).thenReturn(prepared);
    when(prepared.access()).thenReturn(access);
    when(access.actor()).thenReturn(actor);
    when(fingerprints.of(command)).thenReturn("fingerprint-a");
    var pending = new RecordCreateReceiptClaim("record-a", Instant.EPOCH, null);
    when(receipts.find(actor, command, "fingerprint-a"))
        .thenReturn(null, pending, pending, new RecordCreateReceiptClaim("record-a", Instant.EPOCH, result));
    when(transactions.hasCommittedCreate(prepared, "record-a")).thenReturn(false, true);
    when(receipts.complete(actor, command, "fingerprint-a", result)).thenReturn(result);
    var coordinator = new RecordCreateCoordinator(transactions, receipts, fingerprints);
    assertThat(coordinator.findResult(command)).isNull();
    assertThat(coordinator.findResult(command)).isNull();
    assertThat(coordinator.findResult(command)).isEqualTo(result);
    assertThat(coordinator.findResult(command)).isEqualTo(result);
    verify(transactions, org.mockito.Mockito.times(4)).validatePreparedCreate(prepared);
    verify(transactions, org.mockito.Mockito.never()).executePreparedCreate(any(), any(), any());
    verify(receipts, org.mockito.Mockito.never()).claim(any(), any(), any());
  }
}
