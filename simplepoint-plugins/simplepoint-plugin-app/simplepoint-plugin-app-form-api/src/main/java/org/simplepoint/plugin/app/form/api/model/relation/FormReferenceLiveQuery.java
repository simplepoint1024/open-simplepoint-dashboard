package org.simplepoint.plugin.app.form.api.model.relation;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import java.util.UUID;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;

/** A read-only selection; the published relation owns every target and output field identity. */
public record FormReferenceLiveQuery(
    ApplicationFormRuntimeIdentity runtime, @JsonProperty(required = true) String targetRecordId) {
  /** Explicit null clears the display, while an omitted selection is invalid. */
  public FormReferenceLiveQuery {
    Objects.requireNonNull(runtime, "runtime");
    if (targetRecordId != null
        && !UUID.fromString(targetRecordId).toString().equals(targetRecordId)) {
      throw new IllegalArgumentException("A canonical record identity is required");
    }
  }
}
