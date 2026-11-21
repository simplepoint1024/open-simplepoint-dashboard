package org.simplepoint.plugin.dna.runtime.service.record;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.HashSet;
import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;

/** Immutable internal envelope; public snapshot responses remain authorized record projections. */
record RecordStoredSnapshot(
    int contractVersion,
    long calculationRevision,
    String calculationChecksum,
    OwnedRecordView values,
    @JsonInclude(JsonInclude.Include.NON_NULL) List<RecordReferenceCopySnapshot> copyOrigins,
    @JsonInclude(JsonInclude.Include.NON_NULL) List<RecordReferenceLabelOrigin> labelOrigins) {
  RecordStoredSnapshot(
      int contractVersion,
      long calculationRevision,
      String calculationChecksum,
      OwnedRecordView values,
      List<RecordReferenceCopySnapshot> copyOrigins) {
    this(contractVersion, calculationRevision, calculationChecksum, values, copyOrigins, null);
  }

  RecordStoredSnapshot(
      int contractVersion,
      long calculationRevision,
      String calculationChecksum,
      OwnedRecordView values) {
    this(contractVersion, calculationRevision, calculationChecksum, values, null);
  }

  RecordStoredSnapshot {
    if (contractVersion != 2 && contractVersion != 3 && contractVersion != 4 && contractVersion != 5
        || calculationRevision < 0
        || values == null
        || (calculationRevision == 0
            ? calculationChecksum != null
            : calculationChecksum == null || !calculationChecksum.matches("[a-f0-9]{64}"))) {
      throw new IllegalArgumentException("Snapshot provenance is invalid");
    }
    if (contractVersion != 5
        && (contractVersion == 2
            ? copyOrigins != null
            : copyOrigins == null || copyOrigins.isEmpty())) {
      throw new IllegalArgumentException(
          "Snapshot version does not match its protected copy origins");
    }
    if (copyOrigins != null) {
      copyOrigins = List.copyOf(copyOrigins);
      boolean derived = copyOrigins.stream().anyMatch(row -> !row.derivedOrigins().isEmpty());
      if (contractVersion != 5 && (contractVersion == 4) != derived) {
        throw new IllegalArgumentException(
            "Derived provenance requires its explicit snapshot version");
      }
      if (copyOrigins.size() > 201
          || copyOrigins.stream()
                  .mapToInt(row -> row.origins().size() + row.derivedOrigins().size())
                  .sum()
              > 10_050
          || copyOrigins.stream()
                      .flatMap(row -> row.origins().stream())
                      .mapToInt(origin -> origin.requirements().size())
                      .sum()
                  + copyOrigins.stream()
                      .flatMap(row -> row.derivedOrigins().stream())
                      .mapToInt(origin -> origin.requirements().size())
                      .sum()
              > 16_384) {
        throw new IllegalArgumentException("Copied snapshot exceeds its origin budget");
      }
      var seen = new HashSet<List<String>>();
      for (var origin : copyOrigins) {
        var rows =
            origin.detailModelId() == null
                ? List.of(values.parent())
                : values.details().getOrDefault(origin.detailModelId(), List.of());
        if (!seen.add(
                List.of(
                    origin.detailModelId() == null ? "" : origin.detailModelId(),
                    origin.sourceRecordId()))
            || rows.stream()
                .noneMatch(
                    row ->
                        row.id().equals(origin.sourceRecordId())
                            && row.version() == origin.sourceVersion())) {
          throw new IllegalArgumentException(
              "Copy origin is not part of this exact aggregate snapshot");
        }
      }
    }
    if (contractVersion == 5
        ? labelOrigins == null || labelOrigins.isEmpty()
        : labelOrigins != null) {
      throw new IllegalArgumentException(
          "Frozen label origins require their explicit snapshot version");
    }
    if (labelOrigins != null) {
      labelOrigins = List.copyOf(labelOrigins);
      var labels =
          new java.util.LinkedHashMap<
              List<String>,
              org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceSnapshot>();
      for (var label : values.referenceSnapshots()) {
        if (labels.putIfAbsent(RecordReferenceLabelOrigin.key(label), label) != null) {
          throw RecordReferenceSnapshotProvenance.invalid();
        }
      }
      var seen = new HashSet<List<String>>();
      int requirements =
          copyOrigins == null
              ? 0
              : copyOrigins.stream()
                  .mapToInt(
                      row ->
                          row.origins().stream()
                                  .mapToInt(origin -> origin.requirements().size())
                                  .sum()
                              + row.derivedOrigins().stream()
                                  .mapToInt(origin -> origin.requirements().size())
                                  .sum())
                  .sum();
      for (var origin : labelOrigins) {
        var label = labels.get(origin.key());
        if (label == null || !seen.add(origin.key())) {
          throw RecordReferenceSnapshotProvenance.invalid();
        }
        origin.validate(label);
        requirements += origin.requirements().size();
        if (requirements > 16384) {
          throw RecordReferenceSnapshotProvenance.invalid();
        }
      }
      if (!seen.equals(labels.keySet())) {
        throw RecordReferenceSnapshotProvenance.invalid();
      }
    }
  }

  static RecordStoredSnapshot capture(
      final OwnedRecordView values, final RecordCalculationStore.Installed installed) {
    return new RecordStoredSnapshot(
        2,
        installed == null ? 0 : installed.revision(),
        installed == null ? null : RecordCalculationStore.policyChecksum(installed.policy()),
        values);
  }

  RecordStoredSnapshot withCopyOrigins(List<RecordReferenceCopySnapshot> origins) {
    return origins.isEmpty()
        ? this
        : new RecordStoredSnapshot(
            labelOrigins != null
                ? 5
                : origins.stream().anyMatch(row -> !row.derivedOrigins().isEmpty()) ? 4 : 3,
            calculationRevision,
            calculationChecksum,
            values,
            origins,
            labelOrigins);
  }

  RecordStoredSnapshot withLabelOrigins(List<RecordReferenceLabelOrigin> origins) {
    return origins.isEmpty()
        ? this
        : new RecordStoredSnapshot(
            5, calculationRevision, calculationChecksum, values, copyOrigins, origins);
  }
}
