package org.simplepoint.plugin.dna.runtime.service.record;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordCalculationSchema;

/**
 * Bounded immutable definition codec and Calcite SQL. The caller holds the model write fence on
 * the same physical connection. Insert only in the activation transaction, never for staging:
 * cancellation also consumes policy versions and must not expose an abandoned definition.
 */
final class RecordCalculationStore {
  private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
      .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  private static final int MAX_BYTES = 512 * 1024;
  private static final String TABLE = "\"runtime\".\"" + RecordCalculationSchema.TABLE + "\"";

  /** Resolves the last installed definition at the admitted version, retaining it across plain publications. */
  RecordSelectPlan lookup(final RecordWriteFenceKey key, final long admittedVersion) {
    if (admittedVersion < 0) {
      throw new IllegalArgumentException("An admitted policy version is required");
    }
    return new RecordSelectPlan("SELECT \"id\", \"tenant_id\", \"model_id\", \"policy_revision\", "
        + "\"definition_checksum\", \"definition_json\" FROM " + TABLE
        + " WHERE \"tenant_id\" = ? AND \"model_id\" = ? AND \"policy_revision\" <= ?"
        + " AND \"deleted_at\" IS NULL ORDER BY \"policy_revision\" DESC FETCH NEXT 1 ROWS ONLY",
        List.of(key.tenantId(), key.modelId(), admittedVersion), List.of(), 1);
  }

  /** The deterministic primary key rejects a second definition for an already consumed revision. */
  RecordSqlStatement insert(final RecordWriteFenceKey key, final long revision, final String actorId,
      final Instant at, final RecordCalculationPolicy policy) {
    if (revision < 1 || actorId == null || actorId.isBlank() || actorId.length() > 64) {
      throw new IllegalArgumentException("Calculation installation requires a positive revision and actor");
    }
    String json = encode(policy);
    String id = id(key, revision);
    var parameters = new RecordSqlParameters();
    String placeholders = List.of(id, key.tenantId(), 0L, actorId, Timestamp.from(at), actorId,
        Timestamp.from(at), key.modelId(), revision, checksum(json), json).stream()
        .map(parameters::add).collect(java.util.stream.Collectors.joining(", "));
    return new RecordSqlStatement("INSERT INTO " + TABLE
        + " (\"id\", \"tenant_id\", \"record_version\", \"created_by\", \"created_at\", \"updated_by\","
        + " \"updated_at\", \"model_id\", \"policy_revision\", \"definition_checksum\", \"definition_json\") VALUES ("
        + placeholders + ")", parameters.values(), RecordMutationType.CREATE, id, 0, at);
  }

  /** Checks row identity and content integrity before exposing any expression to a compiler. */
  Installed read(final RecordWriteFenceKey key, final long admittedVersion, final CalciteQueryResult result) {
    if (admittedVersion < 0 || result.rows().size() > 1) {
      throw invalid(null);
    }
    if (result.rows().isEmpty()) {
      return null;
    }
    var row = result.rows().getFirst();
    if (row.size() != 6) {
      throw invalid(null);
    }
    try {
      long revision = new BigDecimal(row.get(3).toString()).longValueExact();
      if (revision < 1 || revision > admittedVersion || !id(key, revision).equals(row.get(0))
          || !key.tenantId().equals(row.get(1)) || !key.modelId().equals(row.get(2))
          || !(row.get(5) instanceof String json) || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES
          || !checksum(json).equals(row.get(4))) {
        throw invalid(null);
      }
      return new Installed(revision, JSON.readValue((String) row.get(5), RecordCalculationPolicy.class));
    } catch (JsonProcessingException | IllegalArgumentException | ArithmeticException | NullPointerException failure) {
      throw invalid(failure);
    }
  }

  private static String encode(final RecordCalculationPolicy policy) {
    if (policy == null) {
      throw new IllegalArgumentException("Use an explicit empty definition to remove calculations");
    }
    try {
      String json = JSON.writeValueAsString(policy);
      if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
        throw new IllegalArgumentException("Calculation definition storage budget exceeded");
      }
      return json;
    } catch (JsonProcessingException failure) {
      throw invalid(failure);
    }
  }

  static String policyChecksum(final RecordCalculationPolicy policy) {
    return checksum(encode(policy));
  }

  private static String id(final RecordWriteFenceKey key, final long revision) {
    return RecordRequestFingerprint.deterministicRecordId(key.tenantId(), key.modelId(), "calculation-policy:" + revision);
  }

  private static String checksum(final String json) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
  }

  private static DnaRuntimeException invalid(final Throwable failure) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.PERSISTENCE_FAILED, "Stored calculation definition is invalid", failure);
  }

  record Installed(long revision, RecordCalculationPolicy policy) {
    Installed {
      if (revision < 1 || policy == null) {
        throw new IllegalArgumentException("An installed calculation definition is required");
      }
    }
  }
}
