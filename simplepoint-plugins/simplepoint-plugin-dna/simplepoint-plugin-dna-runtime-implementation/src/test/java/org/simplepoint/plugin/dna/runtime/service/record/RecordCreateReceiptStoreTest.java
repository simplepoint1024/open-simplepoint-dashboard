package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.entity.RecordCreateReceiptEntity;
import org.simplepoint.plugin.dna.runtime.entity.RecordCreateReceiptStatus;
import org.simplepoint.plugin.dna.runtime.repository.RecordCreateReceiptRepository;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

class RecordCreateReceiptStoreTest {

  private static final RecordSchemaReference SCHEMA = new RecordSchemaReference(
      1,
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  );
  private static final RuntimeActor ACTOR = new RuntimeActor("tenant-a", "user-a");

  @Test
  void claimsDeterministicPendingIdentity() {
    RecordCreateReceiptRepository repository = mock(RecordCreateReceiptRepository.class);
    when(repository.findByIdempotencyKeyForUpdate("tenant-a", "model-a", "request-1"))
        .thenReturn(Optional.empty());
    when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    RecordCreateReceiptStore store = new RecordCreateReceiptStore(repository);

    RecordCreateReceiptClaim claim = store.claim(ACTOR, command(), "fingerprint-a");

    assertThat(claim.recordId()).isEqualTo(RecordRequestFingerprint.deterministicRecordId(
        "tenant-a",
        "model-a",
        "request-1"
    ));
    assertThat(claim.operationAt()).isNotNull();
    assertThat(claim.completedResult()).isNull();
    ArgumentCaptor<RecordCreateReceiptEntity> receipt =
        ArgumentCaptor.forClass(RecordCreateReceiptEntity.class);
    org.mockito.Mockito.verify(repository).saveAndFlush(receipt.capture());
    assertThat(receipt.getValue().getStatus()).isEqualTo(RecordCreateReceiptStatus.PENDING);
    assertThat(receipt.getValue().getRequestFingerprint()).isEqualTo("fingerprint-a");
  }

  @Test
  void rejectsReusedKeyForDifferentRequest() {
    RecordCreateReceiptRepository repository = mock(RecordCreateReceiptRepository.class);
    when(repository.findByIdempotencyKeyForUpdate("tenant-a", "model-a", "request-1"))
        .thenReturn(Optional.of(receipt("fingerprint-a", RecordCreateReceiptStatus.PENDING)));
    RecordCreateReceiptStore store = new RecordCreateReceiptStore(repository);

    assertThatThrownBy(() -> store.claim(ACTOR, command(), "fingerprint-b"))
        .isInstanceOfSatisfying(DnaRuntimeException.class, exception ->
            assertThat(exception.errorCode())
                .isEqualTo(DnaRuntimeErrorCode.RECORD_IDEMPOTENCY_CONFLICT));
  }

  @Test
  void replaysThePersistedFirstResult() {
    RecordCreateReceiptRepository repository = mock(RecordCreateReceiptRepository.class);
    RecordCreateReceiptEntity receipt = receipt(
        "fingerprint-a",
        RecordCreateReceiptStatus.SUCCEEDED
    );
    receipt.setResultUpdatedAt(Instant.parse("2026-10-13T01:02:03Z"));
    receipt.setCompletedAt(Instant.parse("2026-10-13T01:02:04Z"));
    when(repository.findByIdempotencyKeyForUpdate("tenant-a", "model-a", "request-1"))
        .thenReturn(Optional.of(receipt));
    RecordCreateReceiptStore store = new RecordCreateReceiptStore(repository);

    RecordCreateReceiptClaim claim = store.claim(ACTOR, command(), "fingerprint-a");

    assertThat(claim.completedResult()).isEqualTo(new RecordCommandResult(
        RecordMutationType.CREATE,
        receipt.getRecordId(),
        0,
        1,
        receipt.getResultUpdatedAt()
    ));
  }

  @Test
  void completesPendingReceiptWithTheFirstResult() {
    RecordCreateReceiptRepository repository = mock(RecordCreateReceiptRepository.class);
    RecordCreateReceiptEntity receipt = receipt(
        "fingerprint-a",
        RecordCreateReceiptStatus.PENDING
    );
    when(repository.findByIdempotencyKeyForUpdate("tenant-a", "model-a", "request-1"))
        .thenReturn(Optional.of(receipt));
    when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
    RecordCreateReceiptStore store = new RecordCreateReceiptStore(repository);
    RecordCommandResult created = new RecordCommandResult(
        RecordMutationType.CREATE,
        receipt.getRecordId(),
        0,
        1,
        Instant.parse("2026-10-13T01:02:03.123456Z")
    );

    assertThat(store.complete(ACTOR, command(), "fingerprint-a", created))
        .isEqualTo(created);
    assertThat(receipt.getStatus()).isEqualTo(RecordCreateReceiptStatus.SUCCEEDED);
    assertThat(receipt.getResultUpdatedAt()).isEqualTo(created.updatedAt());
    assertThat(receipt.getCompletedAt()).isNotNull();
  }

  private static RecordCreateCommand command() {
    return new RecordCreateCommand(
        "model-a",
        SCHEMA,
        "request-1",
        Map.of("title", "First")
    );
  }

  @Test
  void lookupDoesNotReserveMissingKeysAndRejectsAnotherActorOrPayload() {
    var repository = mock(RecordCreateReceiptRepository.class);
    var store = new RecordCreateReceiptStore(repository);
    when(repository.findByIdempotencyKey("tenant-a", "model-a", "request-1")).thenReturn(Optional.empty());
    assertThat(store.find(ACTOR, command(), "fingerprint-a")).isNull();
    org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).saveAndFlush(any());
    var receipt = receipt("fingerprint-a", RecordCreateReceiptStatus.PENDING);
    when(repository.findByIdempotencyKey("tenant-a", "model-a", "request-1")).thenReturn(Optional.of(receipt));
    assertThat(store.find(ACTOR, command(), "fingerprint-a").recordId()).isEqualTo(receipt.getRecordId());
    assertThatThrownBy(() -> store.find(ACTOR, command(), "different"))
        .isInstanceOfSatisfying(DnaRuntimeException.class,
            failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_IDEMPOTENCY_CONFLICT));
    assertThatThrownBy(() -> store.find(new RuntimeActor("tenant-a", "user-b"), command(), "fingerprint-a"))
        .isInstanceOfSatisfying(DnaRuntimeException.class,
            failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_ACCESS_DENIED));
  }

  private static RecordCreateReceiptEntity receipt(
      final String fingerprint,
      final RecordCreateReceiptStatus status
  ) {
    RecordCreateReceiptEntity receipt = new RecordCreateReceiptEntity();
    receipt.setId("receipt-1");
    receipt.setTenantId("tenant-a");
    receipt.setPhysicalModelId("model-a");
    receipt.setIdempotencyKey("request-1");
    receipt.setRequestFingerprint(fingerprint);
    receipt.setRecordId("f28512b0-9e64-5c9a-90e8-c77441ad2303");
    receipt.setStatus(status);
    receipt.setCreatedBy("user-a");
    receipt.setCreatedAt(Instant.parse("2026-10-13T01:02:03Z"));
    return receipt;
  }
}
