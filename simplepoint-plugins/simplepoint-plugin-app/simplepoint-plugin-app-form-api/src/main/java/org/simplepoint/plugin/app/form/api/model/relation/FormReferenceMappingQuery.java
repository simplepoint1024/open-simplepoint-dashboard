package org.simplepoint.plugin.app.form.api.model.relation;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;

/** Source operation and explicit selection; target models and permissions are server-owned. */
public record FormReferenceMappingQuery(
    ApplicationFormRuntimeIdentity runtime,
    String recordId,
    Long expectedVersion,
    @JsonProperty(required = true) String targetRecordId,
    List<String> applyDefaultMappingIds,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        String sourceRowId,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        Boolean newDetailRow) {
  /** Existing callers retain the conservative aggregate lifecycle policy. */
  public FormReferenceMappingQuery(
      ApplicationFormRuntimeIdentity runtime,
      String recordId,
      Long expectedVersion,
      String targetRecordId,
      List<String> applyDefaultMappingIds) {
    this(runtime, recordId, expectedVersion, targetRecordId, applyDefaultMappingIds, null, null);
  }

  /** Bounds suggestions and distinguishes explicit unlinking from an absent request property. */
  public FormReferenceMappingQuery {
    Objects.requireNonNull(runtime, "runtime");
    if ((recordId == null) != (expectedVersion == null)
        || expectedVersion != null && expectedVersion < 0) {
      throw new IllegalArgumentException("Invalid source record observation");
    }
    if (sourceRowId != null && (recordId == null || newDetailRow != null)
        || newDetailRow != null && !newDetailRow) {
      throw new IllegalArgumentException("Invalid source detail observation");
    }
    canonical(sourceRowId);
    canonical(recordId);
    canonical(targetRecordId);
    applyDefaultMappingIds =
        applyDefaultMappingIds == null ? List.of() : List.copyOf(applyDefaultMappingIds);
    if (applyDefaultMappingIds.size() > 32
        || applyDefaultMappingIds.stream().distinct().count() != applyDefaultMappingIds.size()) {
      throw new IllegalArgumentException("Invalid default mapping selection");
    }
    applyDefaultMappingIds.forEach(FormRelationIdentity::require);
    applyDefaultMappingIds = applyDefaultMappingIds.stream().sorted().toList();
  }

  private static void canonical(String id) {
    if (id != null && !UUID.fromString(id).toString().equals(id)) {
      throw new IllegalArgumentException("A canonical record identity is required");
    }
  }
}
