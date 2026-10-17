package org.simplepoint.plugin.dna.runtime.service.record;

import java.time.Instant;
import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;
import org.springframework.stereotype.Component;

/** Coordinates durable single-create idempotency across control and target databases. */
@Component
class RecordCreateCoordinator {

  private static final String REFERENCE = "command";
  private static final int TIMEOUT_MS = 30_000;

  private final RecordTransactionServiceImpl transactions;
  private final RecordCreateReceiptStore receipts;
  private final RecordRequestFingerprint fingerprints;

  RecordCreateCoordinator(
      final RecordTransactionServiceImpl transactions,
      final RecordCreateReceiptStore receipts,
      final RecordRequestFingerprint fingerprints
  ) {
    this.transactions = transactions;
    this.receipts = receipts;
    this.fingerprints = fingerprints;
  }

  RecordCommandResult create(final RecordCreateCommand command) {
    PreparedRecordTransaction prepared = prepare(command);
    String fingerprint = fingerprints.of(command);
    RecordCreateReceiptClaim claim = claim(prepared, command, fingerprint);
    if (claim.completedResult() != null) {
      return claim.completedResult();
    }
    if (transactions.hasCommittedCreate(prepared, claim.recordId())) {
      return complete(prepared, command, fingerprint, claim.recordId(), claim.operationAt());
    }
    try {
      RecordCommandResult created = transactions.executePreparedCreate(
          prepared,
          claim.recordId(),
          claim.operationAt()
      );
      return receipts.complete(
          prepared.access().actor(),
          command,
          fingerprint,
          created
      );
    } catch (RuntimeException failure) {
      try {
        if (transactions.hasCommittedCreate(prepared, claim.recordId())) {
          return complete(
              prepared,
              command,
              fingerprint,
              claim.recordId(),
              claim.operationAt()
          );
        }
      } catch (RuntimeException recoveryFailure) {
        failure.addSuppressed(recoveryFailure);
      }
      throw failure;
    }
  }

  RecordCommandResult findResult(final RecordCreateCommand command) {
    PreparedRecordTransaction prepared = prepare(command);
    String fingerprint = fingerprints.of(command);
    RecordCreateReceiptClaim claim = receipts.find(prepared.access().actor(), command, fingerprint);
    if (claim == null) {
      return null;
    }
    if (claim.completedResult() != null) {
      return claim.completedResult();
    }
    return transactions.hasCommittedCreate(prepared, claim.recordId())
        ? complete(prepared, command, fingerprint, claim.recordId(), claim.operationAt()) : null;
  }

  private PreparedRecordTransaction prepare(final RecordCreateCommand command) {
    var transaction = new RecordTransactionCommand(command.physicalModelId(), command.schemaReference(),
        List.of(new RecordCreateMutation(REFERENCE, command.values())), TIMEOUT_MS, null, command.writePolicyVersion());
    PreparedRecordTransaction prepared = transactions.prepare(transaction);
    transactions.validatePreparedCreate(prepared);
    return prepared;
  }

  private RecordCreateReceiptClaim claim(
      final PreparedRecordTransaction prepared,
      final RecordCreateCommand command,
      final String fingerprint
  ) {
    try {
      return receipts.claim(prepared.access().actor(), command, fingerprint);
    } catch (RecordCreateReceiptRaceException exception) {
      return receipts.load(prepared.access().actor(), command, fingerprint);
    }
  }

  private RecordCommandResult complete(
      final PreparedRecordTransaction prepared,
      final RecordCreateCommand command,
      final String fingerprint,
      final String recordId,
      final Instant createdAt
  ) {
    return receipts.complete(
        prepared.access().actor(),
        command,
        fingerprint,
        new RecordCommandResult(
            RecordMutationType.CREATE,
            recordId,
            0L,
            1,
            createdAt
        )
    );
  }
}
