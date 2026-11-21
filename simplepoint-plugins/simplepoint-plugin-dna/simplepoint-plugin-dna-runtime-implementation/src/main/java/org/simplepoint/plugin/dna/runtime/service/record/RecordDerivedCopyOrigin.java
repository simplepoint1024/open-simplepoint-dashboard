package org.simplepoint.plugin.dna.runtime.service.record;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceReadRequirement;

/**
 * A calculated value's original authorization inputs, distinct from a foreign-reference mapping.
 * Retaining or editing the stored output does not replace these inputs with a later formula graph.
 * Only outputs with external copy dependencies use this ledger; ordinary formulas are unchanged.
 */
record RecordDerivedCopyOrigin(
    @JsonProperty(required = true) int contractVersion,
    String destinationFieldId,
    @JsonProperty(required = true) long calculationRevision,
    String calculationChecksum,
    List<RecordAggregateReadRequirement> localRequirements,
    List<RecordReferenceReadRequirement> requirements) {
  RecordDerivedCopyOrigin {
    if (contractVersion != 1
        || calculationRevision < 1
        || destinationFieldId == null
        || !destinationFieldId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
        || calculationChecksum == null
        || !calculationChecksum.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("Invalid derived copy calculation identity");
    }
    if (localRequirements == null
        || localRequirements.isEmpty()
        || localRequirements.size() > 206
        || requirements == null
        || requirements.isEmpty()
        || requirements.size() > 256) {
      throw new IllegalArgumentException(
          "Derived copy requirements are missing or exceed their budget");
    }
    Set<List<String>> local = new HashSet<>();
    Set<List<String>> external = new HashSet<>();
    Set<String> models = new HashSet<>();
    for (var requirement : localRequirements) {
      if (requirement == null || !local.add(requirement.rowIdentity())) {
        throw new IllegalArgumentException("Duplicate derived aggregate input identity");
      }
      models.add(requirement.modelId());
    }
    if (models.size() > 6
        || localRequirements.stream().mapToInt(requirement -> requirement.fieldIds().size()).sum()
            > 4096) {
      throw new IllegalArgumentException("Derived aggregate input budget exceeded");
    }
    for (var requirement : requirements) {
      if (requirement == null || !external.add(requirement.rowIdentity())) {
        throw new IllegalArgumentException("Duplicate derived external input identity");
      }
    }
    localRequirements =
        localRequirements.stream()
            .sorted(
                Comparator.comparing(RecordAggregateReadRequirement::modelId)
                    .thenComparing(
                        RecordAggregateReadRequirement::recordId,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
            .toList();
    requirements =
        requirements.stream()
            .sorted(
                Comparator.comparing(
                    requirement -> String.join("\u0000", requirement.rowIdentity())))
            .toList();
  }
}
