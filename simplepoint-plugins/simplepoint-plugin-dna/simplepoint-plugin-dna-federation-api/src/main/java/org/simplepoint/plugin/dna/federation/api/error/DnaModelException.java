package org.simplepoint.plugin.dna.federation.api.error;

import java.util.Objects;

/** A stable model catalog failure; messages contain no query text or connection credentials. */
public final class DnaModelException extends RuntimeException {
  private final DnaModelErrorCode code;

  /** Captures the domain diagnosis independently of an HTTP response. */
  public DnaModelException(DnaModelErrorCode code, String message) {
    super(message);
    this.code = Objects.requireNonNull(code, "code");
  }

  /** Returns the stable diagnostic code. */
  public DnaModelErrorCode errorCode() {
    return code;
  }
}
