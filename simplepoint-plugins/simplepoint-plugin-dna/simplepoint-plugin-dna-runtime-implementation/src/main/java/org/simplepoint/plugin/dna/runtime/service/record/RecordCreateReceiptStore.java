package org.simplepoint.plugin.dna.runtime.service.record;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;
import org.simplepoint.plugin.dna.runtime.entity.RecordCreateReceiptEntity;
import org.simplepoint.plugin.dna.runtime.entity.RecordCreateReceiptStatus;
import org.simplepoint.plugin.dna.runtime.repository.RecordCreateReceiptRepository;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Short control-plane transactions around target-database record creation. */
@Component
public class RecordCreateReceiptStore {

  private final RecordCreateReceiptRepository repository;

  /** Creates the durable receipt store. */
  public RecordCreateReceiptStore(final RecordCreateReceiptRepository repository) {
    this.repository = repository;
  }

  /** Claims or reopens one key in an isolated control-plane transaction. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public RecordCreateReceiptClaim claim(
      final RuntimeActor actor,
      final RecordCreateCommand command,
      final String fingerprint
  ) {
    RecordCreateReceiptEntity existing = repository.findByIdempotencyKeyForUpdate(
        actor.tenantId(),
        command.physicalModelId(),
        command.idempotencyKey()
    ).orElse(null);
    if (existing != null) {
      return toClaim(existing, actor, fingerprint);
    }
    Instant operationAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    RecordCreateReceiptEntity receipt = new RecordCreateReceiptEntity();
    receipt.setId(UUID.randomUUID().toString());
    receipt.setTenantId(actor.tenantId());
    receipt.setPhysicalModelId(command.physicalModelId());
    receipt.setIdempotencyKey(command.idempotencyKey());
    receipt.setRequestFingerprint(fingerprint);
    receipt.setRecordId(RecordRequestFingerprint.deterministicRecordId(
        actor.tenantId(),
        command.physicalModelId(),
        command.idempotencyKey()
    ));
    receipt.setStatus(RecordCreateReceiptStatus.PENDING);
    receipt.setCreatedBy(actor.actorId());
    receipt.setUpdatedBy(actor.actorId());
    receipt.setCreatedAt(operationAt);
    receipt.setUpdatedAt(operationAt);
    try {
      repository.saveAndFlush(receipt);
    } catch (DataIntegrityViolationException exception) {
      throw new RecordCreateReceiptRaceException(exception);
    }
    return new RecordCreateReceiptClaim(receipt.getRecordId(), operationAt, null);
  }

  /** Loads the winner after a concurrent unique-key insert race. */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public RecordCreateReceiptClaim load(
      final RuntimeActor actor,
      final RecordCreateCommand command,
      final String fingerprint
  ) {
    RecordCreateReceiptEntity receipt = repository.findByIdempotencyKey(
        actor.tenantId(),
        command.physicalModelId(),
        command.idempotencyKey()
    ).orElseThrow(() -> new DnaRuntimeException(
        DnaRuntimeErrorCode.PERSISTENCE_FAILED,
        "Record create receipt is unavailable after a concurrent claim"
    ));
    return toClaim(receipt, actor, fingerprint);
  }

  /** Looks up an existing intent without allocating a receipt for an uncommitted request. */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public RecordCreateReceiptClaim find(
      final RuntimeActor actor, final RecordCreateCommand command, final String fingerprint
  ) {
    return repository.findByIdempotencyKey(actor.tenantId(), command.physicalModelId(), command.idempotencyKey())
        .map(receipt -> toClaim(receipt, actor, fingerprint)).orElse(null);
  }

  /** Persists and returns the first successful result for all future replays. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public RecordCommandResult complete(
      final RuntimeActor actor,
      final RecordCreateCommand command,
      final String fingerprint,
      final RecordCommandResult result
  ) {
    RecordCreateReceiptEntity receipt = repository.findByIdempotencyKeyForUpdate(
        actor.tenantId(),
        command.physicalModelId(),
        command.idempotencyKey()
    ).orElseThrow(() -> new DnaRuntimeException(
        DnaRuntimeErrorCode.PERSISTENCE_FAILED,
        "Record create receipt is unavailable"
    ));
    requireOwner(receipt, actor);
    requireFingerprint(receipt, fingerprint);
    if (receipt.getStatus() == RecordCreateReceiptStatus.SUCCEEDED) {
      return result(receipt);
    }
    if (result.operation() != RecordMutationType.CREATE
        || !receipt.getRecordId().equals(result.recordId())
        || result.version() != 0L || result.affectedRows() != 1) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.PERSISTENCE_FAILED,
          "Record create result does not match its durable receipt"
      );
    }
    Instant completedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    receipt.setStatus(RecordCreateReceiptStatus.SUCCEEDED);
    receipt.setResultUpdatedAt(result.updatedAt());
    receipt.setCompletedAt(completedAt);
    receipt.setUpdatedBy(actor.actorId());
    receipt.setUpdatedAt(completedAt);
    repository.save(receipt);
    return result(receipt);
  }

  private static RecordCreateReceiptClaim toClaim(
      final RecordCreateReceiptEntity receipt,
      final RuntimeActor actor,
      final String fingerprint
  ) {
    requireOwner(receipt, actor);
    requireFingerprint(receipt, fingerprint);
    RecordCommandResult result = receipt.getStatus() == RecordCreateReceiptStatus.SUCCEEDED
        ? result(receipt) : null;
    return new RecordCreateReceiptClaim(receipt.getRecordId(), receipt.getCreatedAt(), result);
  }

  private static void requireOwner(final RecordCreateReceiptEntity receipt, final RuntimeActor actor) {
    if (!actor.actorId().equals(receipt.getCreatedBy())) {
      throw new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_ACCESS_DENIED,
          "Record create receipt belongs to a different actor");
    }
  }

  private static void requireFingerprint(
      final RecordCreateReceiptEntity receipt,
      final String fingerprint
  ) {
    if (!receipt.getRequestFingerprint().equals(fingerprint)) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.RECORD_IDEMPOTENCY_CONFLICT,
          "Idempotency key was already used for a different record create request"
      );
    }
  }

  private static RecordCommandResult result(final RecordCreateReceiptEntity receipt) {
    if (receipt.getResultUpdatedAt() == null) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.PERSISTENCE_FAILED,
          "Completed record create receipt has no result timestamp"
      );
    }
    return new RecordCommandResult(
        RecordMutationType.CREATE,
        receipt.getRecordId(),
        0L,
        1,
        receipt.getResultUpdatedAt()
    );
  }
}
