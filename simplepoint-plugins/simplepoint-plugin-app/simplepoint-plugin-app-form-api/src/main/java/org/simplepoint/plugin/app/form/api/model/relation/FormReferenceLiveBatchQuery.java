package org.simplepoint.plugin.app.form.api.model.relation;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;

/** Bounded hydration for one published relation, shared by all of its visible detail rows. */
public record FormReferenceLiveBatchQuery(
    ApplicationFormRuntimeIdentity runtime,
    @JsonProperty(required = true) List<String> targetRecordIds) {
  /** Keeps request order while rejecting ambiguous, duplicate and unbounded selections. */
  public FormReferenceLiveBatchQuery {
    Objects.requireNonNull(runtime, "runtime");
    targetRecordIds = List.copyOf(targetRecordIds);
    if (targetRecordIds.size() > 100
        || targetRecordIds.stream().distinct().count() != targetRecordIds.size()
        || targetRecordIds.stream().anyMatch(id -> !UUID.fromString(id).toString().equals(id))) {
      throw new IllegalArgumentException("Invalid live reference selection batch");
    }
  }
}
