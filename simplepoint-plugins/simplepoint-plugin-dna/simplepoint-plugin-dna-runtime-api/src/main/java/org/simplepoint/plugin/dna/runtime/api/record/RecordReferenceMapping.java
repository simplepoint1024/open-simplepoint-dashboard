package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.Objects;

/** Stable scalar copy policy. Live display is a read projection, never a stored value provider. */
public record RecordReferenceMapping(
    String mappingId,
    String targetFieldId,
    String destinationFieldId,
    Mode mode,
    RecordReferenceConversion conversion,
    NullPolicy nullPolicy) {
  /** Rejects incomplete or misleading provenance semantics before model resolution. */
  public RecordReferenceMapping {
    RecordCalculationDefinition.identity(mappingId);
    RecordCalculationDefinition.identity(targetFieldId);
    RecordCalculationDefinition.identity(destinationFieldId);
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(conversion, "conversion");
    Objects.requireNonNull(nullPolicy, "nullPolicy");
    if (mode == Mode.SERVER_SNAPSHOT && nullPolicy == NullPolicy.PRESERVE) {
      throw new IllegalArgumentException("Managed reference copies cannot retain unrelated values");
    }
  }

  /** Managed writes and editable suggestions have separate result channels. */
  public enum Mode {
    SERVER_SNAPSHOT,
    EDITABLE_DEFAULT
  }

  /**
   * Preserving a current value is an editable suggestion, never authoritative copied provenance.
   */
  public enum NullPolicy {
    CLEAR,
    PRESERVE,
    REJECT
  }
}
