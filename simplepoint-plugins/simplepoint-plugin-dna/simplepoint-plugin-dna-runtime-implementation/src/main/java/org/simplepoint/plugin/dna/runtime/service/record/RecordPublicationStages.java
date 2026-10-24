package org.simplepoint.plugin.dna.runtime.service.record;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordPublicationStageSchema;

/**
 * Parameterized stage storage. The caller owns the target fence and one physical transaction for
 * all chunks, the manifest and checkpoint; apply/restore markers commit with actual business writes.
 * No payload or status is sufficient authority to install a definition or advance admission.
 */
final class RecordPublicationStages {
  private static final String TABLE = "\"runtime\".\"" + RecordPublicationStageSchema.TABLE + "\"";
  private static final String CHUNKS = "\"runtime\".\"" + RecordPublicationStageSchema.CHUNKS + "\"";
  private static final String COLUMNS = "\"id\", \"tenant_id\", \"model_id\", \"target_revision\", \"publication_id\","
      + " \"publication_fingerprint\", \"intent_checksum\", \"parent_record_id\", \"record_version\", \"phase\","
      + " \"payload_checksum\", \"payload_bytes\", \"chunk_count\"";
  private static final int CHUNK_PAGE = 32;

  Prepared prepare(final RecordPublicationIntent intent, final RecordPublicationAggregate aggregate) {
    aggregate.requireIntent(intent);
    String payload = RecordPublicationPayload.encode(aggregate);
    var header = new Header(RecordPublicationStore.intentChecksum(intent), aggregate.parentRecordId(), Phase.STAGED,
        RecordPublicationPayload.checksum(payload), payload.length(),
        (payload.length() + RecordPublicationPayload.CHUNK_BYTES - 1) / RecordPublicationPayload.CHUNK_BYTES);
    return new Prepared(intent, header, payload);
  }

  /** Generates statements lazily and inserts the manifest last; never retain a second whole payload copy. */
  Iterable<RecordSqlStatement> inserts(final Prepared prepared) {
    return () -> IntStream.rangeClosed(0, prepared.header.chunkCount()).mapToObj(index -> index == prepared.header.chunkCount()
        ? manifest(prepared) : chunk(prepared, index)).iterator();
  }

  RecordSelectPlan lookup(final RecordPublicationIntent intent, final String parentId) {
    RecordPublicationAggregate.requireId(parentId);
    var parameters = new RecordSqlParameters();
    String predicate = scope(intent, parameters) + " AND \"id\" = " + parameters.add(id(intent, parentId));
    return new RecordSelectPlan("SELECT " + COLUMNS + " FROM " + TABLE + " WHERE " + predicate,
        parameters.values(), List.of(), 2);
  }

  /** SQL collation owns stable keyset ordering; callers must not reorder a fetched batch in Java. */
  RecordSelectPlan page(final RecordPublicationIntent intent, final Phase phase, final String cursor, final int limit) {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("Publication stage batch must contain 1 to 100 aggregates");
    }
    var parameters = new RecordSqlParameters();
    String predicate = scope(intent, parameters) + " AND \"phase\" = " + parameters.add(phase.name());
    if (cursor != null) {
      RecordPublicationAggregate.requireId(cursor);
      predicate += " AND \"parent_record_id\" > " + parameters.add(cursor);
    }
    return new RecordSelectPlan("SELECT " + COLUMNS + " FROM " + TABLE + " WHERE " + predicate
        + " ORDER BY \"parent_record_id\" ASC FETCH NEXT " + limit + " ROWS ONLY", parameters.values(), List.of(), limit);
  }

  RecordSelectPlan counts(final RecordPublicationIntent intent) {
    var parameters = new RecordSqlParameters();
    return new RecordSelectPlan("SELECT \"phase\", COUNT(*) FROM " + TABLE + " WHERE " + scope(intent, parameters)
        + " GROUP BY \"phase\"", parameters.values(), List.of(), 4);
  }

  Map<Phase, Long> readCounts(final CalciteQueryResult result) {
    var counts = new java.util.EnumMap<Phase, Long>(Phase.class);
    if (result.truncated() || result.rows().size() > Phase.values().length) {
      throw RecordPublicationPayload.invalid(null);
    }
    try {
      for (var row : result.rows()) {
        if (row.size() != 2) {
          throw RecordPublicationPayload.invalid(null);
        }
        var phase = Phase.valueOf((String) row.getFirst());
        long count = number(row.get(1));
        if (count < 1 || counts.put(phase, count) != null) {
          throw RecordPublicationPayload.invalid(null);
        }
      }
      return Map.copyOf(counts);
    } catch (IllegalArgumentException | ArithmeticException | ClassCastException | NullPointerException failure) {
      throw RecordPublicationPayload.invalid(failure);
    }
  }

  Header read(final RecordPublicationIntent intent, final String parentId, final CalciteQueryResult result) {
    var headers = readHeaders(intent, result);
    if (headers.size() > 1 || !headers.isEmpty() && !parentId.equals(headers.getFirst().parentRecordId())) {
      throw RecordPublicationPayload.invalid(null);
    }
    return headers.isEmpty() ? null : headers.getFirst();
  }

  List<Header> readPage(final RecordPublicationIntent intent, final Phase phase, final String cursor, final int limit,
      final CalciteQueryResult result) {
    page(intent, phase, cursor, limit);
    var headers = readHeaders(intent, result);
    if (headers.size() > limit || headers.stream().anyMatch(header -> header.phase() != phase || header.parentRecordId().equals(cursor))) {
      throw RecordPublicationPayload.invalid(null);
    }
    return headers;
  }

  /** Reassembles bounded pages, checking every identity/index/digest and the final canonical envelope. */
  RecordPublicationAggregate payload(final RecordPublicationIntent intent, final Header header,
      final Function<RecordSelectPlan, CalciteQueryResult> query) {
    if (!header.intentChecksum().equals(RecordPublicationStore.intentChecksum(intent))) {
      throw RecordPublicationPayload.invalid(null);
    }
    var value = new StringBuilder();
    String stageId = id(intent, header.parentRecordId());
    for (int offset = 0; offset < header.chunkCount(); offset += CHUNK_PAGE) {
      int count = Math.min(CHUNK_PAGE, header.chunkCount() - offset);
      var plan = new RecordSelectPlan("SELECT \"id\", \"tenant_id\", \"stage_id\", \"chunk_index\", \"chunk_checksum\", \"chunk_text\""
          + " FROM " + CHUNKS + " WHERE \"tenant_id\" = ? AND \"stage_id\" = ? AND \"chunk_index\" >= ? AND \"chunk_index\" < ?"
          + " AND \"deleted_at\" IS NULL ORDER BY \"chunk_index\" ASC",
          List.of(intent.key().tenantId(), stageId, offset, offset + count), List.of(), count + 1);
      var rows = query.apply(plan).rows();
      if (rows.size() != count) {
        throw RecordPublicationPayload.invalid(null);
      }
      for (int index = 0; index < count; index++) {
        value.append(readChunk(intent, header, stageId, offset + index, rows.get(index)));
      }
    }
    String json = value.toString();
    if (json.length() != header.payloadBytes() || !RecordPublicationPayload.checksum(json).equals(header.payloadChecksum())) {
      throw RecordPublicationPayload.invalid(null);
    }
    try {
      var aggregate = RecordPublicationPayload.decode(json);
      aggregate.requireIntent(intent);
      if (!aggregate.parentRecordId().equals(header.parentRecordId())) {
        throw RecordPublicationPayload.invalid(null);
      }
      return aggregate;
    } catch (IllegalArgumentException | NullPointerException failure) {
      throw RecordPublicationPayload.invalid(failure);
    }
  }

  /** CAS binds immutable payload, frozen intent, scope and expected phase/sequence. */
  RecordSqlStatement advance(final RecordPublicationIntent intent, final Header previous, final Phase next, final Instant at) {
    if (previous.phase() == Phase.RESTORED || next.sequence() != previous.phase().sequence() + 1) {
      throw new IllegalArgumentException("Invalid publication stage transition");
    }
    RecordPublicationAggregate.requireTime(at.toString());
    var parameters = new RecordSqlParameters();
    String setters = "\"phase\" = " + parameters.add(next.name()) + ", \"record_version\" = " + parameters.add(next.sequence())
        + ", \"updated_by\" = " + parameters.add(intent.actorId()) + ", \"updated_at\" = " + parameters.add(Timestamp.from(at));
    String stageId = id(intent, previous.parentRecordId());
    String predicate = scope(intent, parameters) + " AND \"id\" = " + parameters.add(stageId)
        + " AND \"parent_record_id\" = " + parameters.add(previous.parentRecordId())
        + " AND \"phase\" = " + parameters.add(previous.phase().name()) + " AND \"record_version\" = " + parameters.add(previous.phase().sequence())
        + " AND \"payload_checksum\" = " + parameters.add(previous.payloadChecksum())
        + " AND \"payload_bytes\" = " + parameters.add(previous.payloadBytes()) + " AND \"chunk_count\" = " + parameters.add(previous.chunkCount());
    return new RecordSqlStatement("UPDATE " + TABLE + " SET " + setters + " WHERE " + predicate, parameters.values(),
        RecordMutationType.UPDATE, stageId, next.sequence(), at);
  }

  private List<Header> readHeaders(final RecordPublicationIntent intent, final CalciteQueryResult result) {
    if (result.rows().size() > 100) {
      throw RecordPublicationPayload.invalid(null);
    }
    var resultHeaders = new ArrayList<Header>();
    var parents = new HashSet<String>();
    String intentChecksum = RecordPublicationStore.intentChecksum(intent);
    for (var row : result.rows()) {
      try {
        if (row.size() != 13) {
          throw RecordPublicationPayload.invalid(null);
        }
        var header = new Header((String) row.get(6), (String) row.get(7), Phase.valueOf((String) row.get(9)), (String) row.get(10),
            Math.toIntExact(number(row.get(11))), Math.toIntExact(number(row.get(12))));
        if (!parents.add(header.parentRecordId()) || !id(intent, header.parentRecordId()).equals(row.get(0))
            || !intent.key().tenantId().equals(row.get(1)) || !intent.key().modelId().equals(row.get(2))
            || intent.command().targetVersion() != number(row.get(3)) || !publicationId(intent).equals(row.get(4))
            || !intent.fingerprint().equals(row.get(5)) || !intentChecksum.equals(row.get(6))
            || header.phase().sequence() != number(row.get(8))) {
          throw RecordPublicationPayload.invalid(null);
        }
        resultHeaders.add(header);
      } catch (IllegalArgumentException | ArithmeticException | ClassCastException | NullPointerException failure) {
        throw RecordPublicationPayload.invalid(failure);
      }
    }
    return List.copyOf(resultHeaders);
  }

  private static String readChunk(final RecordPublicationIntent intent, final Header header, final String stageId,
      final int index, final List<Object> row) {
    try {
      if (row.size() != 6 || !chunkId(intent, stageId, index).equals(row.get(0)) || !intent.key().tenantId().equals(row.get(1))
          || !stageId.equals(row.get(2)) || number(row.get(3)) != index || !(row.get(5) instanceof String text)
          || text.length() != Math.min(RecordPublicationPayload.CHUNK_BYTES, header.payloadBytes() - index * RecordPublicationPayload.CHUNK_BYTES)
          || !RecordPublicationPayload.ascii(text) || !RecordPublicationPayload.checksum(text).equals(row.get(4))) {
        throw RecordPublicationPayload.invalid(null);
      }
      return (String) row.get(5);
    } catch (IllegalArgumentException | ArithmeticException | NullPointerException failure) {
      throw RecordPublicationPayload.invalid(failure);
    }
  }

  private static RecordSqlStatement manifest(final Prepared prepared) {
    var intent = prepared.intent;
    var header = prepared.header;
    String stageId = id(intent, header.parentRecordId());
    var values = base(intent, stageId);
    values.put("model_id", intent.key().modelId());
    values.put("target_revision", intent.command().targetVersion());
    values.put("publication_id", publicationId(intent));
    values.put("publication_fingerprint", intent.fingerprint());
    values.put("intent_checksum", RecordPublicationStore.intentChecksum(intent));
    values.put("parent_record_id", header.parentRecordId());
    values.put("phase", header.phase().name());
    values.put("payload_checksum", header.payloadChecksum());
    values.put("payload_bytes", header.payloadBytes());
    values.put("chunk_count", header.chunkCount());
    return insert(TABLE, values, stageId, intent.clock());
  }

  private static RecordSqlStatement chunk(final Prepared prepared, final int index) {
    var intent = prepared.intent;
    String stageId = id(intent, prepared.header.parentRecordId());
    String chunkId = chunkId(intent, stageId, index);
    int offset = index * RecordPublicationPayload.CHUNK_BYTES;
    String text = prepared.payload.substring(offset, Math.min(prepared.payload.length(), offset + RecordPublicationPayload.CHUNK_BYTES));
    var values = base(intent, chunkId);
    values.put("stage_id", stageId);
    values.put("chunk_index", index);
    values.put("chunk_checksum", RecordPublicationPayload.checksum(text));
    values.put("chunk_text", text);
    return insert(CHUNKS, values, chunkId, intent.clock());
  }

  private static Map<String, Object> base(final RecordPublicationIntent intent, final String id) {
    var values = new LinkedHashMap<String, Object>();
    values.put("id", id);
    values.put("tenant_id", intent.key().tenantId());
    values.put("record_version", 1L);
    values.put("created_by", intent.actorId());
    values.put("created_at", Timestamp.from(intent.clock()));
    values.put("updated_by", intent.actorId());
    values.put("updated_at", Timestamp.from(intent.clock()));
    return values;
  }

  private static RecordSqlStatement insert(final String table, final Map<String, Object> values, final String id, final Instant at) {
    var parameters = new RecordSqlParameters();
    String columns = values.keySet().stream().map(column -> "\"" + column + "\"").collect(java.util.stream.Collectors.joining(", "));
    String bindings = values.values().stream().map(parameters::add).collect(java.util.stream.Collectors.joining(", "));
    return new RecordSqlStatement("INSERT INTO " + table + " (" + columns + ") VALUES (" + bindings + ")",
        parameters.values(), RecordMutationType.CREATE, id, 1, at);
  }

  private static String scope(final RecordPublicationIntent intent, final RecordSqlParameters parameters) {
    return "\"tenant_id\" = " + parameters.add(intent.key().tenantId()) + " AND \"model_id\" = " + parameters.add(intent.key().modelId())
        + " AND \"target_revision\" = " + parameters.add(intent.command().targetVersion())
        + " AND \"publication_id\" = " + parameters.add(publicationId(intent))
        + " AND \"publication_fingerprint\" = " + parameters.add(intent.fingerprint())
        + " AND \"intent_checksum\" = " + parameters.add(RecordPublicationStore.intentChecksum(intent)) + " AND \"deleted_at\" IS NULL";
  }

  private static String publicationId(final RecordPublicationIntent intent) {
    return RecordPublicationStore.id(intent.key(), intent.command().targetVersion());
  }

  private static String id(final RecordPublicationIntent intent, final String parentId) {
    return RecordRequestFingerprint.deterministicRecordId(intent.key().tenantId(), publicationId(intent), "aggregate:" + parentId);
  }

  private static String chunkId(final RecordPublicationIntent intent, final String stageId, final int index) {
    return RecordRequestFingerprint.deterministicRecordId(intent.key().tenantId(), stageId, "chunk:" + index);
  }

  private static long number(final Object value) {
    if (!(value instanceof Number)) {
      throw RecordPublicationPayload.invalid(null);
    }
    return new BigDecimal(value.toString()).longValueExact();
  }

  enum Phase {
    STAGED(1), APPLIED(2), RESTORED(3);

    private final long sequence;

    Phase(final long sequence) {
      this.sequence = sequence;
    }

    long sequence() {
      return sequence;
    }
  }

  record Header(String intentChecksum, String parentRecordId, Phase phase, String payloadChecksum,
      int payloadBytes, int chunkCount) {
    Header {
      RecordPublicationAggregate.requireId(parentRecordId);
      Objects.requireNonNull(phase, "phase");
      if (!RecordPublicationIntent.checksum(intentChecksum) || !RecordPublicationIntent.checksum(payloadChecksum)
          || payloadBytes < 1 || payloadBytes > RecordPublicationPayload.MAX_BYTES
          || chunkCount != (payloadBytes + RecordPublicationPayload.CHUNK_BYTES - 1) / RecordPublicationPayload.CHUNK_BYTES) {
        throw new IllegalArgumentException("Invalid publication aggregate manifest");
      }
    }

    Header withPhase(final Phase next) {
      return new Header(intentChecksum, parentRecordId, next, payloadChecksum, payloadBytes, chunkCount);
    }
  }

  static final class Prepared {
    private final RecordPublicationIntent intent;
    private final Header header;
    private final String payload;

    private Prepared(final RecordPublicationIntent intent, final Header header, final String payload) {
      this.intent = intent;
      this.header = header;
      this.payload = payload;
    }

    Header header() {
      return header;
    }
  }
}
