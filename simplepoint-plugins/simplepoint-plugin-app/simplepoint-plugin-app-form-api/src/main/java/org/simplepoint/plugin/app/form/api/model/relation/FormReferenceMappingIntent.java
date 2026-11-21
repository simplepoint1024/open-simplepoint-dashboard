package org.simplepoint.plugin.app.form.api.model.relation;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * Immutable mapping intent, saved alongside business values instead of embedding trusted copies.
 */
public record FormReferenceMappingIntent(
    String contractVersion, List<FormReferenceMappingConfirmation> confirmations) {
  /**
   * Canonicalizes irrelevant confirmation order while retaining meaningful detail row positions.
   */
  public FormReferenceMappingIntent {
    if (!"1".equals(contractVersion) || confirmations == null || confirmations.size() > 10_050) {
      throw new IllegalArgumentException("Invalid form reference mapping intent");
    }
    confirmations = List.copyOf(confirmations);
    var seen = new HashSet<Coordinate>();
    for (var confirmation : confirmations) {
      if (!seen.add(
          new Coordinate(
              confirmation.relationId(), confirmation.detailId(), confirmation.rowIndex()))) {
        throw new IllegalArgumentException("Duplicate row relation confirmation");
      }
    }
    confirmations =
        confirmations.stream()
            .sorted(
                Comparator.comparing(
                        FormReferenceMappingConfirmation::detailId,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(
                        FormReferenceMappingConfirmation::rowIndex,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(FormReferenceMappingConfirmation::relationId))
            .toList();
  }

  private record Coordinate(String relationId, String detailId, Integer rowIndex) {}
}
