package org.simplepoint.plugin.app.form.api.model.relation;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** User confirmation bound to one stable relation and one proposed aggregate row. */
public record FormReferenceMappingConfirmation(
    String relationId,
    String detailId,
    Integer rowIndex,
    String sourceRowId,
    FormReferenceMappingObservation observation,
    List<String> applyDefaultMappingIds) {
  /**
   * Detail identity is a form ID; physical model identities are never accepted from the browser.
   */
  public FormReferenceMappingConfirmation {
    relationId = FormRelationIdentity.require(relationId);
    Objects.requireNonNull(observation, "observation");
    if (detailId == null) {
      if (rowIndex != null || sourceRowId != null) {
        throw new IllegalArgumentException("Parent mapping cannot identify a detail row");
      }
    } else {
      detailId = FormRelationIdentity.require(detailId);
      if (rowIndex == null || rowIndex < 0 || rowIndex >= 40) {
        throw new IllegalArgumentException("Mapping detail position is outside its row budget");
      }
      if (sourceRowId != null && !UUID.fromString(sourceRowId).toString().equals(sourceRowId)) {
        throw new IllegalArgumentException("A canonical detail row identity is required");
      }
    }
    applyDefaultMappingIds =
        applyDefaultMappingIds == null ? List.of() : List.copyOf(applyDefaultMappingIds);
    if (applyDefaultMappingIds.size() > 32
        || applyDefaultMappingIds.stream().distinct().count() != applyDefaultMappingIds.size()) {
      throw new IllegalArgumentException("Invalid default mapping selection");
    }
    applyDefaultMappingIds.forEach(FormRelationIdentity::require);
    applyDefaultMappingIds = applyDefaultMappingIds.stream().sorted().toList();
  }
}
