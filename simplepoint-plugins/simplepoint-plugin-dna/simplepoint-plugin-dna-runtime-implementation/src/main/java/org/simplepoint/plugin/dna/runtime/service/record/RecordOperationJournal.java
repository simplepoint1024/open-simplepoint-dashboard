package org.simplepoint.plugin.dna.runtime.service.record;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;

/**
 * Bounded receipt codec and parameterized internal SQL, executed through the same Calcite
 * transaction.
 */
final class RecordOperationJournal {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final RecordOperationReceiptStore receipts = new RecordOperationReceiptStore();

  RecordSelectPlan lookup(final RecordOperationIdentity operation) {
    return receipts.lookup(operation);
  }

  RecordSqlStatement insert(
      final RecordOperationIdentity operation, final List<RecordSqlStatement> statements) {
    Instant timestamp = statements.getFirst().updatedAt();
    return insert(operation, timestamp, encode(statements));
  }

  private RecordSqlStatement insert(
      final RecordOperationIdentity operation, final Instant timestamp, final String json) {
    return receipts.insert(operation, timestamp, json);
  }

  /**
   * A durable negative receipt fences delayed/repeated commands after all earlier writers drain.
   */
  RecordSqlStatement reject(final RecordOperationIdentity operation, final Instant timestamp) {
    return reject(operation, timestamp, DnaRuntimeErrorCode.RECORD_OPERATION_REJECTED);
  }

  RecordSqlStatement reject(
      final RecordOperationIdentity operation,
      final Instant timestamp,
      final DnaRuntimeErrorCode code) {
    return insert(operation, timestamp, RecordOperationRejection.encode(code));
  }

  List<RecordCommandResult> read(
      final RecordOperationIdentity operation, final CalciteQueryResult query) {
    String json = receipts.read(operation, query);
    if (json == null) {
      return null;
    }
    try {
      var entries = JSON.readTree(json);
      RecordOperationRejection.requireSuccess(entries);
      if (!entries.isArray() || entries.isEmpty() || entries.size() > 100) {
        throw invalid(null);
      }
      List<RecordCommandResult> result = new ArrayList<>();
      for (var entry : entries) {
        if (!entry.path("version").isIntegralNumber()
            || !entry.path("version").canConvertToLong()
            || entry.path("version").longValue() < 0
            || !entry.path("id").isTextual()
            || entry.path("id").asText().isBlank()) {
          throw invalid(null);
        }
        result.add(
            new RecordCommandResult(
                RecordMutationType.valueOf(entry.path("type").asText()),
                entry.path("id").asText(),
                entry.path("version").longValue(),
                1,
                Instant.parse(entry.path("at").asText())));
      }
      return List.copyOf(result);
    } catch (JsonProcessingException
        | IllegalArgumentException
        | java.time.DateTimeException exception) {
      throw invalid(exception);
    }
  }

  private String encode(final List<RecordSqlStatement> statements) {
    var entries = JSON.createArrayNode();
    for (RecordSqlStatement statement : statements) {
      entries
          .addObject()
          .put("type", statement.mutationType().name())
          .put("id", statement.recordId())
          .put("version", statement.resultVersion())
          .put("at", statement.updatedAt().toString());
    }
    return entries.toString();
  }

  private static DnaRuntimeException invalid(final Throwable cause) {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.PERSISTENCE_FAILED, "Record operation receipt is invalid", cause);
  }

  static DnaRuntimeException rejected() {
    return rejected(DnaRuntimeErrorCode.RECORD_OPERATION_REJECTED);
  }

  static DnaRuntimeException rejected(DnaRuntimeErrorCode code) {
    return RecordOperationRejection.rejected(code);
  }
}
