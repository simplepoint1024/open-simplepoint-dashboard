package org.simplepoint.plugin.dna.runtime.service.record;

import com.fasterxml.jackson.annotation.JsonInclude;
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

/**
 * Immutable computed-column patches and exact undo metadata; never an authorized/projection
 * snapshot.
 */
record RecordPublicationAggregate(
    int contractVersion, String parentRecordId, List<Row> rows,
    @JsonInclude(JsonInclude.Include.NON_NULL) RecordCollectionRebind.Edge reboundEdge) {
  RecordPublicationAggregate(int contractVersion, String parentRecordId, List<Row> rows) {
    this(contractVersion, parentRecordId, rows, null);
  }

  static RecordPublicationAggregate edge(RecordCollectionRebind.Edge edge) {
    return new RecordPublicationAggregate(3, edge.id(), List.of(), edge);
  }

  RecordPublicationAggregate {
    requireId(parentRecordId);
    rows = List.copyOf(rows);
    if (contractVersion == 3) {
      if (reboundEdge == null || !parentRecordId.equals(reboundEdge.id()) || !rows.isEmpty()) {
        throw new IllegalArgumentException("Invalid staged collection edge");
      }
    } else {
      validateRows(contractVersion, parentRecordId, rows, reboundEdge);
    }
  }

  private static void validateRows(int contractVersion, String parentRecordId, List<Row> rows,
      RecordCollectionRebind.Edge reboundEdge) {
    if (contractVersion != 1 && contractVersion != 2
        || reboundEdge != null
        || rows.isEmpty()
        || rows.size() > 201
        || (contractVersion == 2) != rows.stream().anyMatch(row -> row.copyChange() != null)) {
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
      } else if (!parentRecordId.equals(row.parentRecordId())
          || row.changes().isEmpty() && row.copyChange() == null
          || counts.merge(row.modelId(), 1, Integer::sum) > 40) {
        throw new IllegalArgumentException("Invalid publication detail ownership or size");
      }
    }
    if (roots != 1) {
      throw new IllegalArgumentException("A publication aggregate requires exactly one parent");
    }
  }

  /**
   * Stable field identities and owned model scopes must come from the frozen candidate definition.
   */
  void requireIntent(final RecordPublicationIntent intent) {
    var rebind = intent.command().collectionRebind();
    if (rebind != null || reboundEdge != null) {
      if (rebind == null || reboundEdge == null || !reboundEdge.destinationRecordId().equals(
          rebind.targetRecords().get(reboundEdge.originalRecordId()))) {
        throw new IllegalArgumentException("Staged edge does not match its frozen target mapping");
      }
      return;
    }
    var definition = Objects.requireNonNull(intent.command().calculation(), "calculation");
    var scopes = new HashMap<String, java.util.Set<String>>();
    var conversion = intent.command().conversionSource();
    scopes.put(intent.key().modelId(), definition.formulas().keySet());
    definition
        .details()
        .values()
        .forEach(scope -> scopes.put(scope.physicalModelId(), scope.formulas().keySet()));
    for (var row : rows) {
      var fields = scopes.get(row.modelId());
      if ((row.parentRecordId() == null) != row.modelId().equals(intent.key().modelId())
          || fields == null) {
        throw new IllegalArgumentException(
            "Publication patch is outside its frozen calculation scope");
      }
      for (var entry : row.changes().entrySet()) {
        if (fields.contains(entry.getKey())) {
          continue;
        }
        var change = entry.getValue();
        if (conversion == null
            || row.parentRecordId() != null
            || !conversion.source().fieldId().equals(entry.getKey())
            || change.previous().kind() != LogicalValueKind.UUID
            || change.candidate().kind() != LogicalValueKind.NULL) {
          throw new IllegalArgumentException(
              "Publication patch is outside its frozen calculation scope");
        }
        requireId(change.previous().canonicalValue());
      }
      if (row.copyChange() != null) {
        var protectedFields =
            intent.queryProtection().getOrDefault(row.modelId(), java.util.Set.of());
        var candidate = row.copyChange().candidate();
        if (candidate.origins().stream()
                .anyMatch(origin -> !protectedFields.contains(origin.destinationFieldId()))
            || candidate.derivedOrigins().stream()
                .anyMatch(origin -> !protectedFields.contains(origin.destinationFieldId()))) {
          throw new IllegalArgumentException(
              "Publication origin is outside its frozen query protection");
        }
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
  record Row(
      String modelId,
      String recordId,
      String parentRecordId,
      long previousVersion,
      String previousUpdatedBy,
      String previousUpdatedAt,
      Map<String, Change> changes,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          RecordPublicationCopyChange copyChange) {
    Row(
        String modelId,
        String recordId,
        String parentRecordId,
        long previousVersion,
        String previousUpdatedBy,
        String previousUpdatedAt,
        Map<String, Change> changes) {
      this(
          modelId,
          recordId,
          parentRecordId,
          previousVersion,
          previousUpdatedBy,
          previousUpdatedAt,
          changes,
          null);
    }

    Row {
      requireId(recordId);
      if (parentRecordId != null) {
        requireId(parentRecordId);
      }
      requireTime(previousUpdatedAt);
      changes = Map.copyOf(changes);
      if (modelId == null
          || modelId.isBlank()
          || modelId.length() > 64
          || !modelId.equals(modelId.trim())
          || previousVersion < 0
          || previousVersion == Long.MAX_VALUE
          || previousUpdatedBy == null
          || previousUpdatedBy.isBlank()
          || previousUpdatedBy.length() > 64
          || !previousUpdatedBy.equals(previousUpdatedBy.trim())
          || changes.size() > 256
          || changes.keySet().stream()
              .anyMatch(id -> !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))) {
        throw new IllegalArgumentException("Invalid publication row metadata");
      }
      if (copyChange != null && copyChange.candidate().sourceVersion() != previousVersion + 1) {
        throw new IllegalArgumentException("Publication origin and business versions differ");
      }
    }
  }

  /**
   * Exact canonical values; the execution compiler must also validate against the frozen field
   * schema.
   */
  record Change(LogicalValue previous, LogicalValue candidate) {
    Change {
      Objects.requireNonNull(previous, "previous");
      Objects.requireNonNull(candidate, "candidate");
      if (previous.kind() != LogicalValueKind.NULL
          && candidate.kind() != LogicalValueKind.NULL
          && previous.kind() != candidate.kind()) {
        throw new IllegalArgumentException(
            "Publication patches cannot change a column's logical type");
      }
    }
  }
}
