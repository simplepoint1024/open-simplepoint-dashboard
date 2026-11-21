package org.simplepoint.plugin.app.form.api.model.relation;

import java.util.Objects;

/** Stable target-to-local-field mapping; destination belongs to the owning reference's row. */
public record FormReferenceMapping(
    String mappingId,
    String targetFieldId,
    String destinationFieldId,
    Mode mode,
    Conversion conversion,
    NullPolicy nullPolicy) {
  /** Rejects incomplete policies rather than guessing write or null semantics. */
  public FormReferenceMapping {
    mappingId = FormRelationIdentity.require(mappingId);
    targetFieldId = FormRelationIdentity.require(targetFieldId);
    destinationFieldId = FormRelationIdentity.require(destinationFieldId);
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(conversion, "conversion");
    Objects.requireNonNull(nullPolicy, "nullPolicy");
    if (mode == Mode.LIVE && nullPolicy != NullPolicy.CLEAR) {
      throw new IllegalArgumentException("Live mappings cannot retain a previous record's value");
    }
    if (mode == Mode.SERVER_SNAPSHOT && nullPolicy == NullPolicy.PRESERVE) {
      throw new IllegalArgumentException("Managed snapshots cannot retain an unrelated provenance");
    }
  }

  /** Read projection, authoritative copy, or explicitly editable suggestion. */
  public enum Mode {
    LIVE,
    SERVER_SNAPSHOT,
    EDITABLE_DEFAULT
  }

  /** Closed, deterministic conversions; no expressions, locale or timezone-dependent coercion. */
  public enum Conversion {
    IDENTITY,
    STRING,
    INTEGER,
    DECIMAL
  }

  /** PRESERVE is only an editable-default suggestion and never claims copied provenance. */
  public enum NullPolicy {
    CLEAR,
    PRESERVE,
    REJECT
  }
}
