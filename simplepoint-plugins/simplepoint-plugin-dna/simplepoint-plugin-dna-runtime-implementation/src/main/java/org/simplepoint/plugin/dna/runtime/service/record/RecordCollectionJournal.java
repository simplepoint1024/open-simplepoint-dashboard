package org.simplepoint.plugin.dna.runtime.service.record;

import java.time.Instant;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordResult;

/** Collection results use the existing durable ledger without truncating underlying mutations. */
final class RecordCollectionJournal {
  private final RecordOperationReceiptStore receipts = new RecordOperationReceiptStore();

  RecordSelectPlan lookup(RecordOperationIdentity operation) {
    return receipts.lookup(operation);
  }

  RecordSqlStatement insert(
      RecordOperationIdentity operation,
      CollectionRecordCommand command,
      RecordCollectionBinding binding,
      CollectionRecordResult result) {
    requireCommand(operation, command);
    var frozen = RecordCollectionReceipt.accepted(binding, result);
    RecordCollectionReceipt.requireCommand(command, frozen);
    return receipts.insert(operation, result.evaluatedAt(), RecordCollectionReceipt.encode(frozen));
  }

  /** A known constraint rejection cannot turn into a delayed successful link on a later retry. */
  RecordSqlStatement reject(
      RecordOperationIdentity operation, Instant at, DnaRuntimeErrorCode code) {
    return receipts.insert(operation, at, RecordOperationRejection.encode(code));
  }

  /** Only the root/key and frozen receipt are required; current target metadata is never read. */
  CollectionRecordResult read(
      RecordOperationIdentity operation,
      CollectionRecordCommand command,
      CalciteQueryResult query) {
    requireCommand(operation, command);
    String json = receipts.read(operation, query);
    if (json == null) {
      return null;
    }
    var frozen = RecordCollectionReceipt.decode(json);
    RecordCollectionReceipt.requireCommand(command, frozen);
    return frozen.result();
  }

  private static void requireCommand(
      RecordOperationIdentity operation, CollectionRecordCommand command) {
    if (!operation.modelId().equals(command.sourceModelId())
        || !operation.key().equals(command.idempotencyKey())) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.RECORD_IDEMPOTENCY_CONFLICT,
          "The collection command differs from its durable operation identity");
    }
  }
}
