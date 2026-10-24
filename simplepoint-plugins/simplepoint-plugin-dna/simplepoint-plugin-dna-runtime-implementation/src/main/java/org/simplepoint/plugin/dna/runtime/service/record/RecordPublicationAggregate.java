package org.simplepoint.plugin.dna.runtime.service.record;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;

/** Immutable computed-column patches and exact undo metadata; never an authorized/projection snapshot. */
record RecordPublicationAggregate(int contractVersion, String parentRecordId, List<Row> rows) {
  RecordPublicationAggregate {
    requireId(parentRecordId);
    rows = List.copyOf(rows);
    if (contractVersion != 1 || rows.isEmpty() || rows.size() > 201) {
      throw new IllegalArgumentException("Invalid publication aggregate size or version");
    }
    var identities = new HashSet<String>();
    var counts = new HashMap<String, Integer>();
    int roots = 0;
    for (var row : rows) {
      if (!identities.add(row.modelId() + ":" + row.recordId())) {
        throw new IllegalArgumentException("Duplicate publication row");
      }
      if (row.parentRecordId() == null) {
        roots++;
        if (!row.recordId().equals(parentRecordId)) {
          throw new IllegalArgumentException("Publication parent identity differs");
        }
      } else if (!parentRecordId.equals(row.parentRecordId()) || row.changes().isEmpty()
          || counts.merge(row.modelId(), 1, Integer::sum) > 40) {
        throw new IllegalArgumentException("Invalid publication detail ownership or size");
      }
    }
    if (roots != 1) {
      throw new IllegalArgumentException("A publication aggregate requires exactly one parent");
    }
  }

  /** Stable field identities and owned model scopes must come from the frozen candidate definition. */
  void requireIntent(final RecordPublicationIntent intent) {
    var definition = Objects.requireNonNull(intent.command().calculation(), "calculation");
    var scopes = new HashMap<String, java.util.Set<String>>();
    scopes.put(intent.key().modelId(), definition.formulas().keySet());
    definition.details().values().forEach(scope -> scopes.put(scope.physicalModelId(), scope.formulas().keySet()));
    for (var row : rows) {
      if ((row.parentRecordId() == null) != row.modelId().equals(intent.key().modelId())
          || !scopes.containsKey(row.modelId()) || !scopes.get(row.modelId()).containsAll(row.changes().keySet())) {
        throw new IllegalArgumentException("Publication patch is outside its frozen calculation scope");
      }
    }
  }

  static void requireId(final String value) {
    if (value == null || !UUID.fromString(value).toString().equals(value)) {
      throw new IllegalArgumentException("A canonical physical record identity is required");
    }
  }

  static void requireTime(final String value) {
    Instant time = Instant.parse(value);
    if (!time.toString().equals(value) || !time.equals(time.truncatedTo(ChronoUnit.MICROS))) {
      throw new IllegalArgumentException("A canonical microsecond instant is required");
    }
  }

  /** Empty parent patches permit aggregate-version touches when only owned computed rows change. */
  record Row(String modelId, String recordId, String parentRecordId, long previousVersion,
      String previousUpdatedBy, String previousUpdatedAt, Map<String, Change> changes) {
    Row {
      requireId(recordId);
      if (parentRecordId != null) {
        requireId(parentRecordId);
      }
      requireTime(previousUpdatedAt);
      changes = Map.copyOf(changes);
      if (modelId == null || modelId.isBlank() || modelId.length() > 64 || !modelId.equals(modelId.trim())
          || previousVersion < 0 || previousVersion == Long.MAX_VALUE || previousUpdatedBy == null
          || previousUpdatedBy.isBlank() || previousUpdatedBy.length() > 64 || !previousUpdatedBy.equals(previousUpdatedBy.trim())
          || changes.size() > 256 || changes.keySet().stream().anyMatch(id -> !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))) {
        throw new IllegalArgumentException("Invalid publication row metadata");
      }
    }
  }

  /** Exact canonical values; the execution compiler must also validate against the frozen field schema. */
  record Change(LogicalValue previous, LogicalValue candidate) {
    Change {
      Objects.requireNonNull(previous, "previous");
      Objects.requireNonNull(candidate, "candidate");
      if (previous.kind() != LogicalValueKind.NULL && candidate.kind() != LogicalValueKind.NULL
          && previous.kind() != candidate.kind()) {
        throw new IllegalArgumentException("Publication patches cannot change a column's logical type");
      }
    }
  }
}
