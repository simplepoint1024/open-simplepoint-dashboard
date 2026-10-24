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
      new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
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
      RecordOperationIdentity operation, RecordStoredSnapshot snapshot, Instant at) {
    String json;
    try {
      // Keep ordinary legacy snapshots readable during a rolling upgrade or rollback.
      // Any installed definition, including an explicit removal, retains its versioned provenance.
      json = JSON.writeValueAsString(snapshot.calculationRevision() == 0 ? snapshot.values() : snapshot);
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

  RecordStoredSnapshot read(RecordOperationIdentity operation, CalciteQueryResult result) {
    if (result.rows().size() > 1) {
      throw invalid(null);
    }
    if (result.rows().isEmpty()) {
      return null;
    }
    var row = result.rows().getFirst();
    if (row.size() != 6) {
      throw invalid(null);
    }
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
      var tree = JSON.readTree(json);
      if (!tree.isObject()) {
        throw invalid(null);
      }
      if (!tree.has("contractVersion")) {
        // Legacy publications could not install formulas. Preserve their original values.
        return RecordStoredSnapshot.capture(JSON.treeToValue(tree, OwnedRecordView.class), null);
      }
      if (!tree.has("calculationRevision") || !tree.has("calculationChecksum") || !tree.has("values")) {
        throw invalid(null);
      }
      return JSON.treeToValue(tree, RecordStoredSnapshot.class);
    } catch (JsonProcessingException | IllegalArgumentException failure) {
      throw invalid(failure);
    }
  }

  private static DnaRuntimeException invalid(Throwable cause) {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.PERSISTENCE_FAILED, "Immutable record snapshot is invalid", cause);
  }
}
