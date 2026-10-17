package org.simplepoint.plugin.app.form.api.model;

import java.util.Objects;

/** Canonical form metadata value; text is parsed according to its explicit kind. */
public record FormValue(FormValueKind kind, String value) {

  /** Rejects ambiguous null representations. */
  public FormValue {
    Objects.requireNonNull(kind, "kind");
    if (kind == FormValueKind.NULL && value != null
        || kind != FormValueKind.NULL && value == null) {
      throw new IllegalArgumentException("Form value does not match its kind");
    }
    if (value != null && value.length() > 16_384) {
      throw new IllegalArgumentException("Form value exceeds 16384 characters");
    }
  }
}
