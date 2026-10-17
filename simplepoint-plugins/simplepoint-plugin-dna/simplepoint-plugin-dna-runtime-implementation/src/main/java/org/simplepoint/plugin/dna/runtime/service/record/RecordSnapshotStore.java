package org.simplepoint.plugin.dna.runtime.service.record;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordSnapshotSchema;

/** Immutable, bounded value capture, atomically inserted alongside the metadata-only receipt. */
final class RecordSnapshotStore {
  private static final ObjectMapper JSON =
      new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  private static final int MAX_BYTES = 2 * 1024 * 1024;
  private static final String TABLE = "\"runtime\".\"" + RecordSnapshotSchema.TABLE + "\"";

  RecordSelectPlan lookup(RecordOperationIdentity operation) {
    return new RecordSelectPlan(
        "SELECT \"tenant_id\", \"created_by\", \"model_id\", \"operation_key\", "
            + "\"request_fingerprint\", \"snapshot_json\" FROM "
            + TABLE
            + " WHERE \"id\" = ?",
        List.of(operation.id()),
        List.of(),
        1);
  }

  RecordSqlStatement insert(
      RecordOperationIdentity operation, OwnedRecordView snapshot, Instant at) {
    String json;
    try {
      json = JSON.writeValueAsString(snapshot);
    } catch (JsonProcessingException failure) {
      throw invalid(failure);
    }
    if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.RECORD_COMMAND_INVALID, "Business snapshot exceeds 2 MiB");
    }
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
            + " \"request_fingerprint\", \"snapshot_json\") VALUES ("
            + placeholders
            + ")",
        parameters.values(),
        RecordMutationType.CREATE,
        operation.id(),
        0,
        at);
  }

  OwnedRecordView read(RecordOperationIdentity operation, CalciteQueryResult result) {
    if (result.rows().isEmpty()) {
      return null;
    }
    var row = result.rows().getFirst();
    if (!operation.tenantId().equals(row.get(0))
        || !operation.actorId().equals(row.get(1))
        || !operation.modelId().equals(row.get(2))
        || !operation.key().equals(row.get(3))
        || !operation.fingerprint().equals(row.get(4))) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.RECORD_IDEMPOTENCY_CONFLICT,
          "Snapshot does not belong to this operation");
    }
    if (!(row.get(5) instanceof String json)
        || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      throw invalid(null);
    }
    try {
      return JSON.readValue(json, OwnedRecordView.class);
    } catch (JsonProcessingException | IllegalArgumentException failure) {
      throw invalid(failure);
    }
  }

  private static DnaRuntimeException invalid(Throwable cause) {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.PERSISTENCE_FAILED, "Immutable record snapshot is invalid", cause);
  }
}
