package org.simplepoint.plugin.dna.runtime.service.record;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordJournalSchema;

/** Shared durable envelope; payload versions remain owned by their operation's codec. */
final class RecordOperationReceiptStore {
  static final int MAX_PAYLOAD_BYTES = 65_535;
  private static final String TABLE = "\"runtime\".\"" + RecordJournalSchema.TABLE + "\"";

  RecordSelectPlan lookup(RecordOperationIdentity operation) {
    return new RecordSelectPlan(
        "SELECT \"tenant_id\", \"created_by\", \"model_id\", \"operation_key\", "
            + "\"request_fingerprint\", \"result_json\" FROM "
            + TABLE
            + " WHERE \"id\" = ?",
        List.of(operation.id()),
        List.of(),
        1);
  }

  /** The caller executes this statement in the same physical transaction as its business fact. */
  RecordSqlStatement insert(RecordOperationIdentity operation, Instant at, String json) {
    requirePayload(json);
    var parameters = new RecordSqlParameters();
    String placeholders =
        List.of(
                operation.id(),
                operation.tenantId(),
                0L,
                operation.actorId(),
                Timestamp.from(at),
                operation.actorId(),
                Timestamp.from(at),
                operation.modelId(),
                operation.key(),
                operation.fingerprint(),
                json)
            .stream()
            .map(parameters::add)
            .collect(java.util.stream.Collectors.joining(", "));
    return new RecordSqlStatement(
        "INSERT INTO "
            + TABLE
            + " (\"id\", \"tenant_id\", \"record_version\", \"created_by\", \"created_at\","
            + " \"updated_by\", \"updated_at\", \"model_id\", \"operation_key\","
            + " \"request_fingerprint\", \"result_json\") VALUES ("
            + placeholders
            + ")",
        parameters.values(),
        RecordMutationType.CREATE,
        operation.id(),
        0,
        at);
  }

  /** Identity checks precede payload decoding, including versioned negative receipts. */
  String read(RecordOperationIdentity operation, CalciteQueryResult query) {
    if (query == null
        || query.truncated()
        || query.rows().size() > 1
        || query.returnedRows() != query.rows().size()) {
      throw invalid();
    }
    if (query.rows().isEmpty()) {
      return null;
    }
    var row = query.rows().getFirst();
    if (row.size() != 6) {
      throw invalid();
    }
    if (!operation.tenantId().equals(row.get(0))
        || !operation.actorId().equals(row.get(1))
        || !operation.modelId().equals(row.get(2))
        || !operation.key().equals(row.get(3))
        || !operation.fingerprint().equals(row.get(4))) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.RECORD_IDEMPOTENCY_CONFLICT,
          "Idempotency key was already used for a different record operation");
    }
    if (!(row.get(5) instanceof String json)) {
      throw invalid();
    }
    requirePayload(json);
    return json;
  }

  static void requirePayload(String json) {
    if (json == null
        || json.isBlank()
        || json.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
      throw invalid();
    }
  }

  private static DnaRuntimeException invalid() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.PERSISTENCE_FAILED, "Record operation receipt envelope is invalid");
  }
}
