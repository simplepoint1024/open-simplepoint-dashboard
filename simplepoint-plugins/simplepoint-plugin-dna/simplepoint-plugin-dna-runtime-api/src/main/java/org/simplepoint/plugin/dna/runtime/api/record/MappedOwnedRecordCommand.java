package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Dedicated mapping intent cannot be downgraded to an ordinary or inverse aggregate write. */
public record MappedOwnedRecordCommand(
    String contractVersion,
    OwnedRecordCommand command,
    RelatedRecordScope relatedScope,
    List<RecordReferenceMappingConfirmation> confirmations) {
  /** Validate coordinates against this exact request before resolving authoritative policy. */
  public MappedOwnedRecordCommand {
    if (!"1".equals(contractVersion)) {
      throw new IllegalArgumentException("Unsupported mapped aggregate command version");
    }
    Objects.requireNonNull(command, "command");
    if (relatedScope != null) {
      new RelatedRecordCommand(command, relatedScope);
    }
    confirmations = confirmations == null ? List.of() : List.copyOf(confirmations);
    if (confirmations.size() > 10_050
        || !confirmations.isEmpty()
            && command.parent().mutations().getFirst() instanceof RecordDeleteMutation) {
      throw new IllegalArgumentException("Mapped aggregate confirmation budget is invalid");
    }
    Set<Coordinate> seen = new HashSet<>();
    for (var confirmation : confirmations) {
      if (!seen.add(
          new Coordinate(
              confirmation.relationId(), confirmation.detailModelId(), confirmation.rowIndex()))) {
        throw new IllegalArgumentException("Duplicate row relation confirmation");
      }
      if (confirmation.detailModelId() == null) {
        continue;
      }
      var detail =
          command.details().stream()
              .filter(set -> set.model().physicalModelId().equals(confirmation.detailModelId()))
              .findFirst()
              .orElseThrow(() -> new IllegalArgumentException("Mapping detail is absent"));
      if (confirmation.rowIndex() >= detail.rows().size()
          || !Objects.equals(
              confirmation.sourceRowId(), detail.rows().get(confirmation.rowIndex()).id())) {
        throw new IllegalArgumentException("Mapping confirmation belongs to another detail row");
      }
    }
    // Confirmation order carries no meaning; detail row order inside the aggregate still does.
    confirmations =
        confirmations.stream()
            .sorted(
                Comparator.comparing(
                        RecordReferenceMappingConfirmation::detailModelId,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(
                        RecordReferenceMappingConfirmation::rowIndex,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(RecordReferenceMappingConfirmation::relationId))
            .toList();
  }

  private record Coordinate(String relationId, String detailModelId, Integer rowIndex) {}
}
