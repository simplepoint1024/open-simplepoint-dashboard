package org.simplepoint.plugin.dna.runtime.service.record;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceCopyOrigin;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordReferenceCopySchema;

/**
 * SQL plans only: the owning aggregate executes ledger effects in its existing physical
 * transaction.
 */
final class RecordReferenceCopyStore {
  static final int MAX_BYTES = 256 * 1024;
  private static final String TABLE = "\"runtime\".\"" + RecordReferenceCopySchema.TABLE + "\"";
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

  RecordSelectPlan lookup(Key key) {
    return new RecordSelectPlan(
        "SELECT \"tenant_id\", \"model_id\", \"source_record_id\","
            + " \"record_version\", \"origins_json\" FROM "
            + TABLE
            + " WHERE \"id\" = ? AND \"tenant_id\" = ? AND \"model_id\" = ?"
            + " AND \"source_record_id\" = ? AND \"deleted_at\" IS NULL",
        List.of(key.id(), key.tenantId(), key.modelId(), key.recordId()),
        List.of(),
        2);
  }

  /** One bounded model/tenant batch; callers batch rows rather than issuing a query per field. */
  RecordSelectPlan lookupBatch(List<Key> requested) {
    var keys = batchKeys(requested);
    var first = keys.values().iterator().next();
    var parameters = new RecordSqlParameters();
    String predicate =
        "\"tenant_id\" = "
            + parameters.add(first.tenantId())
            + " AND \"model_id\" = "
            + parameters.add(first.modelId());
    String ids =
        keys.values().stream()
            .map(key -> parameters.add(key.id()))
            .collect(java.util.stream.Collectors.joining(", "));
    return new RecordSelectPlan(
        "SELECT \"id\", \"tenant_id\", \"model_id\", \"source_record_id\","
            + " \"record_version\", \"origins_json\" FROM "
            + TABLE
            + " WHERE "
            + predicate
            + " AND \"id\" IN ("
            + ids
            + ") AND \"deleted_at\" IS NULL",
        parameters.values(),
        List.of(),
        keys.size());
  }

  Map<String, Stored> readBatch(List<Key> requested, CalciteQueryResult result) {
    var keys = batchKeys(requested);
    if (result.truncated() || result.rows().size() > keys.size()) {
      throw invalid();
    }
    Map<String, Stored> stored = new LinkedHashMap<>();
    for (var row : result.rows()) {
      if (row.size() != 6 || !(row.get(3) instanceof String recordId)) {
        throw invalid();
      }
      var key = keys.get(recordId);
      if (key == null || !key.id().equals(row.getFirst()) || stored.containsKey(recordId)) {
        throw invalid();
      }
      stored.put(
          recordId,
          read(
              key,
              new CalciteQueryResult(List.of(), List.of(row.subList(1, 6)), false, 1, 0, null)));
    }
    return Map.copyOf(stored);
  }

  private static Map<String, Key> batchKeys(List<Key> requested) {
    if (requested == null || requested.isEmpty() || requested.size() > 100) {
      throw invalid();
    }
    Map<String, Key> keys = new LinkedHashMap<>();
    var first = requested.getFirst();
    if (first == null) {
      throw invalid();
    }
    for (var key : requested) {
      if (key == null
          || !first.tenantId().equals(key.tenantId())
          || !first.modelId().equals(key.modelId())
          || keys.putIfAbsent(key.recordId(), key) != null) {
        throw invalid();
      }
    }
    return keys;
  }

  Stored read(Key key, CalciteQueryResult result) {
    if (result.truncated() || result.rows().size() > 1) {
      throw invalid();
    }
    if (result.rows().isEmpty()) {
      return null;
    }
    var row = result.rows().getFirst();
    if (row.size() != 5
        || !key.tenantId().equals(row.get(0))
        || !key.modelId().equals(row.get(1))
        || !key.recordId().equals(row.get(2))
        || !(row.get(4) instanceof String json)
        || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      throw invalid();
    }
    try {
      long version = new java.math.BigDecimal(row.get(3).toString()).longValueExact();
      var tree = JSON.readTree(json);
      if (tree == null || !tree.isObject() || !tree.path("contractVersion").isIntegralNumber()) {
        throw invalid();
      }
      if (tree.path("contractVersion").intValue() == 1) {
        var envelope = JSON.treeToValue(tree, Envelope.class);
        return new Stored(version, envelope.origins());
      }
      var envelope = JSON.treeToValue(tree, DerivedEnvelope.class);
      return new Stored(version, envelope.origins(), envelope.derivedOrigins());
    } catch (Exception failure) {
      throw invalid();
    }
  }

  RecordSqlStatement write(
      Key key,
      String actor,
      Stored previous,
      long sourceVersion,
      List<RecordReferenceCopyOrigin> origins,
      Instant at) {
    return write(
        key,
        actor,
        previous,
        sourceVersion,
        origins,
        previous == null ? List.of() : previous.derivedOrigins(),
        at);
  }

  RecordSqlStatement write(
      Key key,
      String actor,
      Stored previous,
      long sourceVersion,
      List<RecordReferenceCopyOrigin> origins,
      List<RecordDerivedCopyOrigin> derivedOrigins,
      Instant at) {
    if (actor == null
        || actor.isBlank()
        || actor.length() > 255
        || actor.indexOf('\0') >= 0
        || at == null
        || sourceVersion < 0
        || previous != null && sourceVersion <= previous.sourceVersion()) {
      throw invalid();
    }
    String json = encode(new Stored(sourceVersion, origins, derivedOrigins));
    var parameters = new RecordSqlParameters();
    if (previous == null) {
      String placeholders =
          List.of(
                  key.id(),
                  key.tenantId(),
                  sourceVersion,
                  actor,
                  Timestamp.from(at),
                  actor,
                  Timestamp.from(at),
                  key.modelId(),
                  key.recordId(),
                  json)
              .stream()
              .map(parameters::add)
              .collect(java.util.stream.Collectors.joining(", "));
      return new RecordSqlStatement(
          "INSERT INTO "
              + TABLE
              + " (\"id\", \"tenant_id\", \"record_version\", \"created_by\", \"created_at\","
              + " \"updated_by\", \"updated_at\", \"model_id\", \"source_record_id\","
              + " \"origins_json\") VALUES ("
              + placeholders
              + ")",
          parameters.values(),
          RecordMutationType.CREATE,
          key.id(),
          sourceVersion,
          at);
    }
    String sql =
        "UPDATE "
            + TABLE
            + " SET \"record_version\" = "
            + parameters.add(sourceVersion)
            + ", \"updated_by\" = "
            + parameters.add(actor)
            + ", \"updated_at\" = "
            + parameters.add(Timestamp.from(at))
            + ", \"origins_json\" = "
            + parameters.add(json)
            + " WHERE \"id\" = "
            + parameters.add(key.id())
            + " AND \"tenant_id\" = "
            + parameters.add(key.tenantId())
            + " AND \"model_id\" = "
            + parameters.add(key.modelId())
            + " AND \"source_record_id\" = "
            + parameters.add(key.recordId())
            + " AND \"record_version\" = "
            + parameters.add(previous.sourceVersion())
            + " AND \"deleted_at\" IS NULL";
    return new RecordSqlStatement(
        sql, parameters.values(), RecordMutationType.UPDATE, key.id(), sourceVersion, at);
  }

  private static String encode(Stored checked) {
    final String json;
    try {
      json =
          JSON.writeValueAsString(
              checked.derivedOrigins().isEmpty()
                  ? new Envelope(1, checked.origins())
                  : new DerivedEnvelope(2, checked.origins(), checked.derivedOrigins()));
    } catch (Exception failure) {
      throw invalid();
    }
    if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      throw invalid();
    }
    return json;
  }

  static int encodedBytes(Stored stored) {
    return encode(stored).getBytes(StandardCharsets.UTF_8).length;
  }

  /** Only the durable publication restore phase may reverse a ledger version. */
  RecordSqlStatement restorePublication(
      Key key, String actor, Stored applied, Stored previous, Instant at) {
    if (actor == null
        || actor.isBlank()
        || actor.length() > 255
        || actor.indexOf('\0') >= 0
        || at == null
        || applied == null
        || applied.sourceVersion() < 1
        || previous != null && previous.sourceVersion() != applied.sourceVersion() - 1) {
      throw invalid();
    }
    var parameters = new RecordSqlParameters();
    String prefix;
    if (previous == null) {
      // The staged undo image was absence. History remains in the owning publication journal.
      prefix = "DELETE FROM " + TABLE;
    } else {
      prefix =
          "UPDATE "
              + TABLE
              + " SET \"record_version\" = "
              + parameters.add(previous.sourceVersion())
              + ", \"updated_by\" = "
              + parameters.add(actor)
              + ", \"updated_at\" = "
              + parameters.add(Timestamp.from(at))
              + ", \"origins_json\" = "
              + parameters.add(encode(previous));
    }
    String sql =
        prefix
            + " WHERE \"id\" = "
            + parameters.add(key.id())
            + " AND \"tenant_id\" = "
            + parameters.add(key.tenantId())
            + " AND \"model_id\" = "
            + parameters.add(key.modelId())
            + " AND \"source_record_id\" = "
            + parameters.add(key.recordId())
            + " AND \"record_version\" = "
            + parameters.add(applied.sourceVersion())
            + " AND \"origins_json\" = "
            + parameters.add(encode(applied))
            + " AND \"deleted_at\" IS NULL";
    return new RecordSqlStatement(
        sql,
        parameters.values(),
        previous == null ? RecordMutationType.DELETE : RecordMutationType.UPDATE,
        key.id(),
        previous == null ? 0 : previous.sourceVersion(),
        at);
  }

  record Key(String tenantId, String modelId, String recordId) {
    Key {
      if (tenantId == null
          || tenantId.isBlank()
          || tenantId.length() > 255
          || tenantId.indexOf('\0') >= 0
          || modelId == null
          || modelId.isBlank()
          || modelId.length() > 64
          || modelId.indexOf('\0') >= 0
          || recordId == null) {
        throw invalid();
      }
      try {
        if (!java.util.UUID.fromString(recordId).toString().equals(recordId)) {
          throw invalid();
        }
      } catch (IllegalArgumentException failure) {
        throw invalid();
      }
    }

    String id() {
      return RecordRequestFingerprint.deterministicReferenceCopyId(tenantId, modelId, recordId);
    }
  }

  record Stored(
      long sourceVersion,
      List<RecordReferenceCopyOrigin> origins,
      List<RecordDerivedCopyOrigin> derivedOrigins) {
    Stored(long sourceVersion, List<RecordReferenceCopyOrigin> origins) {
      this(sourceVersion, origins, List.of());
    }

    Stored {
      if (sourceVersion < 0) {
        throw invalid();
      }
      origins = checked(origins);
      derivedOrigins = checkedDerived(origins, derivedOrigins);
    }
  }

  private record DerivedEnvelope(
      int contractVersion,
      List<RecordReferenceCopyOrigin> origins,
      List<RecordDerivedCopyOrigin> derivedOrigins) {
    private DerivedEnvelope {
      if (contractVersion != 2 || derivedOrigins == null || derivedOrigins.isEmpty()) {
        throw invalid();
      }
      var checked = new Stored(0, origins, derivedOrigins);
      origins = checked.origins();
      derivedOrigins = checked.derivedOrigins();
    }
  }

  private static List<RecordDerivedCopyOrigin> checkedDerived(
      List<RecordReferenceCopyOrigin> origins, List<RecordDerivedCopyOrigin> derived) {
    if (derived == null || derived.size() > 256) {
      throw invalid();
    }
    var fields = new HashSet<String>();
    origins.forEach(origin -> fields.add(origin.destinationFieldId()));
    for (var origin : derived) {
      if (origin == null || !fields.add(origin.destinationFieldId())) {
        throw invalid();
      }
    }
    if (origins.stream().mapToInt(origin -> origin.requirements().size()).sum()
                + derived.stream().mapToInt(origin -> origin.requirements().size()).sum()
            > 2048
        || derived.stream()
                .flatMap(origin -> origin.localRequirements().stream())
                .mapToInt(requirement -> requirement.fieldIds().size())
                .sum()
            > 16384) {
      throw invalid();
    }
    return derived.stream()
        .sorted(java.util.Comparator.comparing(RecordDerivedCopyOrigin::destinationFieldId))
        .toList();
  }

  private record Envelope(int contractVersion, List<RecordReferenceCopyOrigin> origins) {
    private Envelope {
      if (contractVersion != 1) {
        throw invalid();
      }
      origins = checked(origins);
    }
  }

  private static List<RecordReferenceCopyOrigin> checked(List<RecordReferenceCopyOrigin> origins) {
    if (origins == null || origins.size() > 128) {
      throw invalid();
    }
    var fields = new HashSet<String>();
    var mappings = new HashSet<List<String>>();
    for (var origin : origins) {
      if (origin == null
          || !fields.add(origin.destinationFieldId())
          || !mappings.add(List.of(origin.relationId(), origin.mappingId()))) {
        throw invalid();
      }
    }
    if (origins.stream().mapToInt(origin -> origin.requirements().size()).sum() > 2048) {
      throw invalid();
    }
    return origins.stream()
        .sorted(java.util.Comparator.comparing(RecordReferenceCopyOrigin::destinationFieldId))
        .toList();
  }

  private static DnaRuntimeException invalid() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.PERSISTENCE_FAILED,
        "Protected reference copy origins are absent or inconsistent");
  }
}
