package org.simplepoint.plugin.dna.runtime.service.record;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordJournalSchema;

/** Bounded receipt codec and parameterized internal SQL, executed through the same Calcite transaction. */
final class RecordOperationJournal {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String TABLE = "\"runtime\".\"" + RecordJournalSchema.TABLE + "\"";
  private static final int MAX_JSON_LENGTH = 65_535;

  RecordSelectPlan lookup(final RecordOperationIdentity operation) {
    return new RecordSelectPlan("SELECT \"tenant_id\", \"created_by\", \"model_id\", \"operation_key\", "
        + "\"request_fingerprint\", \"result_json\" FROM " + TABLE + " WHERE \"id\" = ?",
        List.of(operation.id()), List.of(), 1);
  }

  RecordSqlStatement insert(final RecordOperationIdentity operation, final List<RecordSqlStatement> statements) {
    Instant timestamp = statements.getFirst().updatedAt();
    RecordSqlParameters parameters = new RecordSqlParameters();
    String placeholders = List.of(operation.id(), operation.tenantId(), 0L, operation.actorId(), Timestamp.from(timestamp),
        operation.actorId(), Timestamp.from(timestamp), operation.modelId(), operation.key(),
        operation.fingerprint(), encode(statements)).stream().map(parameters::add)
        .collect(java.util.stream.Collectors.joining(", "));
    return new RecordSqlStatement("INSERT INTO " + TABLE
        + " (\"id\", \"tenant_id\", \"record_version\", \"created_by\", \"created_at\", \"updated_by\", \"updated_at\", "
        + "\"model_id\", \"operation_key\", \"request_fingerprint\", \"result_json\")"
        + " VALUES (" + placeholders + ")", parameters.values(), RecordMutationType.CREATE, operation.id(), 0, timestamp);
  }

  List<RecordCommandResult> read(final RecordOperationIdentity operation, final CalciteQueryResult query) {
    if (query.rows().isEmpty()) {
      return null;
    }
    List<Object> row = query.rows().getFirst();
    if (!operation.tenantId().equals(row.get(0)) || !operation.actorId().equals(row.get(1))
        || !operation.modelId().equals(row.get(2)) || !operation.key().equals(row.get(3))
        || !operation.fingerprint().equals(row.get(4))) {
      throw new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_IDEMPOTENCY_CONFLICT,
          "Idempotency key was already used for a different record operation");
    }
    if (!(row.get(5) instanceof String json) || json.length() > MAX_JSON_LENGTH) {
      throw invalid(null);
    }
    try {
      var entries = JSON.readTree(json);
      if (!entries.isArray() || entries.isEmpty() || entries.size() > 100) {
        throw invalid(null);
      }
      List<RecordCommandResult> result = new ArrayList<>();
      for (var entry : entries) {
        if (!entry.path("version").isIntegralNumber() || !entry.path("version").canConvertToLong() || entry.path("version").longValue() < 0
            || !entry.path("id").isTextual() || entry.path("id").asText().isBlank()) {
          throw invalid(null);
        }
        result.add(new RecordCommandResult(RecordMutationType.valueOf(entry.path("type").asText()),
            entry.path("id").asText(), entry.path("version").longValue(), 1,
            Instant.parse(entry.path("at").asText())));
      }
      return List.copyOf(result);
    } catch (JsonProcessingException | IllegalArgumentException | java.time.DateTimeException exception) {
      throw invalid(exception);
    }
  }

  private String encode(final List<RecordSqlStatement> statements) {
    var entries = JSON.createArrayNode();
    for (RecordSqlStatement statement : statements) {
      entries.addObject().put("type", statement.mutationType().name()).put("id", statement.recordId())
          .put("version", statement.resultVersion()).put("at", statement.updatedAt().toString());
    }
    String json = entries.toString();
    if (json.length() > MAX_JSON_LENGTH) {
      throw invalid(null);
    }
    return json;
  }

  private static DnaRuntimeException invalid(final Throwable cause) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.PERSISTENCE_FAILED,
        "Record operation receipt is invalid", cause);
  }
}
