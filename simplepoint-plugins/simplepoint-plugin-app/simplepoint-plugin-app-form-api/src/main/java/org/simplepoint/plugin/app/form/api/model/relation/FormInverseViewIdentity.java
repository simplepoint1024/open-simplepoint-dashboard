package org.simplepoint.plugin.app.form.api.model.relation;

import java.util.Objects;
import java.util.UUID;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;

/** Browser-observed parent entry and saved identity; source metadata stays server-owned. */
public record FormInverseViewIdentity(
    ApplicationFormRuntimeIdentity runtime, String viewId, String recordId) {
  /** Rejects incomplete identities before any relation metadata or record access. */
  public FormInverseViewIdentity {
    try {
      Objects.requireNonNull(runtime, "runtime");
      viewId = FormRelationIdentity.require(viewId);
      String canonical = UUID.fromString(Objects.requireNonNull(recordId, "recordId")).toString();
      if (!canonical.equalsIgnoreCase(recordId)) {
        throw new IllegalArgumentException("A saved canonical parent identity is required");
      }
      recordId = canonical;
    } catch (IllegalArgumentException | NullPointerException failure) {
      throw new org.simplepoint.plugin.app.form.api.error.ApplicationFormException(
          org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode.RECORD_INVALID,
          "A valid published inverse view and saved parent identity are required");
    }
  }
}
