package org.simplepoint.plugin.dna.runtime.service.record;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordPublicationSchema;

/**
 * Strict journal codec and parameterized Calcite SQL. The caller holds the owning target fence,
 * checks affected rows, and commits the checkpoint with its staged/applied/restored business batch.
 * This class never opens a transaction, advances admission, or installs a calculation definition.
 */
final class RecordPublicationStore {
  private static final ObjectMapper JSON = JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
      .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
  // The APP command budget is 2 MiB; reserve additional space for frozen placement/version metadata.
  private static final int MAX_INTENT_BYTES = 3 * 1024 * 1024;
  private static final int MAX_CHECKPOINT_BYTES = 4096;
  private static final String TABLE = "\"runtime\".\"" + RecordPublicationSchema.TABLE + "\"";
  private static final String COLUMNS = "\"id\", \"tenant_id\", \"model_id\", \"target_revision\","
      + " \"publication_fingerprint\", \"record_version\", \"phase\", \"intent_checksum\", \"intent_json\","
      + " \"checkpoint_checksum\", \"checkpoint_json\"";

  RecordSelectPlan lookup(final RecordWriteFenceKey key, final long targetRevision) {
    return new RecordSelectPlan("SELECT " + COLUMNS + " FROM " + TABLE
        + " WHERE \"id\" = ? AND \"tenant_id\" = ? AND \"model_id\" = ? AND \"target_revision\" = ?"
        + " AND \"deleted_at\" IS NULL", List.of(id(key, targetRevision), key.tenantId(), key.modelId(), targetRevision), List.of(), 2);
  }

  /** Two rows intentionally expose inconsistent concurrent ownership instead of silently choosing one. */
  RecordSelectPlan active(final RecordWriteFenceKey key) {
    return new RecordSelectPlan("SELECT " + COLUMNS + " FROM " + TABLE
        + " WHERE \"tenant_id\" = ? AND \"model_id\" = ? AND \"deleted_at\" IS NULL"
        + " AND \"phase\" NOT IN ('COMMITTED', 'CANCELLED') ORDER BY \"target_revision\" DESC FETCH NEXT 2 ROWS ONLY",
        List.of(key.tenantId(), key.modelId()), List.of(), 2);
  }

  /** A forged terminal phase cannot release access before the policy version was consumed. */
  RecordSelectPlan unresolved(final RecordWriteFenceKey key, final long admittedRevision) {
    if (admittedRevision < 0) {
      throw new IllegalArgumentException("A nonnegative admission revision is required");
    }
    return new RecordSelectPlan("SELECT " + COLUMNS + " FROM " + TABLE
        + " WHERE \"tenant_id\" = ? AND \"model_id\" = ? AND \"deleted_at\" IS NULL"
        + " AND (\"phase\" NOT IN ('COMMITTED', 'CANCELLED') OR \"target_revision\" > ?)"
        + " ORDER BY \"target_revision\" DESC FETCH NEXT 2 ROWS ONLY",
        List.of(key.tenantId(), key.modelId(), admittedRevision), List.of(), 2);
  }

  RecordSqlStatement insert(final Entry entry) {
    var intent = entry.intent();
    var checkpoint = entry.checkpoint();
    if (!checkpoint.equals(RecordPublicationCheckpoint.start(checkpoint.total()))) {
      throw new IllegalArgumentException("A new publication must start at its first checkpoint");
    }
    String intentJson = encode(intent, MAX_INTENT_BYTES);
    String checkpointJson = encode(checkpoint, MAX_CHECKPOINT_BYTES);
    var key = intent.key();
    String id = id(key, intent.command().targetVersion());
    var parameters = new RecordSqlParameters();
    String values = List.of(id, key.tenantId(), checkpoint.sequence(), intent.actorId(), Timestamp.from(intent.clock()),
        intent.actorId(), Timestamp.from(intent.clock()), key.modelId(), intent.command().targetVersion(), intent.fingerprint(),
        checkpoint.phase().name(), checksum(intentJson), intentJson, checksum(checkpointJson), checkpointJson).stream()
        .map(parameters::add).collect(java.util.stream.Collectors.joining(", "));
    return new RecordSqlStatement("INSERT INTO " + TABLE
        + " (\"id\", \"tenant_id\", \"record_version\", \"created_by\", \"created_at\", \"updated_by\", \"updated_at\","
        + " \"model_id\", \"target_revision\", \"publication_fingerprint\", \"phase\", \"intent_checksum\", \"intent_json\","
        + " \"checkpoint_checksum\", \"checkpoint_json\") VALUES (" + values + ")",
        parameters.values(), RecordMutationType.CREATE, id, checkpoint.sequence(), intent.clock());
  }

  /** Compare-and-set also binds the old checkpoint bytes and immutable intent, not just its sequence. */
  RecordSqlStatement advance(final Entry previous, final RecordPublicationCheckpoint next, final Instant at) {
    previous.checkpoint().requireSuccessor(next);
    var intent = previous.intent();
    String nextJson = encode(next, MAX_CHECKPOINT_BYTES);
    String id = id(intent.key(), intent.command().targetVersion());
    var parameters = new RecordSqlParameters();
    String setters = String.join(", ", "\"record_version\" = " + parameters.add(next.sequence()),
        "\"updated_by\" = " + parameters.add(intent.actorId()), "\"updated_at\" = " + parameters.add(Timestamp.from(at)),
        "\"phase\" = " + parameters.add(next.phase().name()), "\"checkpoint_checksum\" = " + parameters.add(checksum(nextJson)),
        "\"checkpoint_json\" = " + parameters.add(nextJson));
    String predicate = String.join(" AND ", "\"id\" = " + parameters.add(id),
        "\"tenant_id\" = " + parameters.add(intent.key().tenantId()), "\"model_id\" = " + parameters.add(intent.key().modelId()),
        "\"target_revision\" = " + parameters.add(intent.command().targetVersion()),
        "\"publication_fingerprint\" = " + parameters.add(intent.fingerprint()),
        "\"record_version\" = " + parameters.add(previous.checkpoint().sequence()),
        "\"intent_checksum\" = " + parameters.add(checksum(encode(intent, MAX_INTENT_BYTES))),
        "\"checkpoint_checksum\" = " + parameters.add(checksum(encode(previous.checkpoint(), MAX_CHECKPOINT_BYTES))),
        "\"deleted_at\" IS NULL");
    return new RecordSqlStatement("UPDATE " + TABLE + " SET " + setters + " WHERE " + predicate, parameters.values(),
        RecordMutationType.UPDATE, id, next.sequence(), at);
  }

  Entry read(final RecordWriteFenceKey key, final long targetRevision, final CalciteQueryResult result) {
    if (targetRevision < 1 || result.rows().size() > 1) {
      throw invalid(null);
    }
    if (result.rows().isEmpty()) {
      return null;
    }
    try {
      var row = result.rows().getFirst();
      if (row.size() != 11 || !id(key, targetRevision).equals(row.get(0)) || !key.tenantId().equals(row.get(1))
          || !key.modelId().equals(row.get(2)) || number(row.get(3)) != targetRevision) {
        throw invalid(null);
      }
      var intent = decode(row.get(8), row.get(7), RecordPublicationIntent.class, MAX_INTENT_BYTES);
      var checkpoint = decode(row.get(10), row.get(9), RecordPublicationCheckpoint.class, MAX_CHECKPOINT_BYTES);
      if (!key.equals(intent.key()) || targetRevision != intent.command().targetVersion()
          || !intent.fingerprint().equals(row.get(4)) || checkpoint.sequence() != number(row.get(5))
          || !checkpoint.phase().name().equals(row.get(6))) {
        throw invalid(null);
      }
      return new Entry(intent, checkpoint);
    } catch (IllegalArgumentException | ArithmeticException | NullPointerException failure) {
      throw invalid(failure);
    }
  }

  Entry readActive(final RecordWriteFenceKey key, final CalciteQueryResult result) {
    if (result.rows().isEmpty()) {
      return null;
    }
    try {
      if (result.rows().size() != 1 || result.rows().getFirst().size() != 11) {
        throw invalid(null);
      }
      var entry = read(key, number(result.rows().getFirst().get(3)), result);
      if (entry.checkpoint().progress() == null) {
        throw invalid(null);
      }
      return entry;
    } catch (IllegalArgumentException | ArithmeticException | NullPointerException failure) {
      throw invalid(failure);
    }
  }

  private static long number(final Object value) {
    return new BigDecimal(value.toString()).longValueExact();
  }

  static String id(final RecordWriteFenceKey key, final long revision) {
    if (revision < 1) {
      throw new IllegalArgumentException("A positive publication target revision is required");
    }
    return RecordRequestFingerprint.deterministicRecordId(key.tenantId(), key.modelId(), "publication:" + revision);
  }

  static String intentChecksum(final RecordPublicationIntent intent) {
    return checksum(encode(intent, MAX_INTENT_BYTES));
  }

  private static String encode(final Object value, final int maximum) {
    try {
      String json = JSON.writeValueAsString(value);
      if (json.getBytes(StandardCharsets.UTF_8).length > maximum) {
        throw new IllegalArgumentException("Publication journal payload budget exceeded");
      }
      return json;
    } catch (JsonProcessingException failure) {
      throw invalid(failure);
    }
  }

  private static <T> T decode(final Object value, final Object digest, final Class<T> type, final int maximum) {
    if (!(value instanceof String json) || json.getBytes(StandardCharsets.UTF_8).length > maximum || !checksum(json).equals(digest)) {
      throw invalid(null);
    }
    try {
      T parsed = JSON.readValue(json, type);
      if (parsed == null || !json.equals(encode(parsed, maximum))) {
        throw invalid(null);
      }
      return parsed;
    } catch (JsonProcessingException failure) {
      throw invalid(failure);
    }
  }

  private static String checksum(final String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
  }

  private static DnaRuntimeException invalid(final Throwable cause) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.PERSISTENCE_FAILED, "Stored publication journal is invalid", cause);
  }

  record Entry(RecordPublicationIntent intent, RecordPublicationCheckpoint checkpoint) {
    Entry {
      Objects.requireNonNull(intent, "intent");
      Objects.requireNonNull(checkpoint, "checkpoint");
    }
  }
}
