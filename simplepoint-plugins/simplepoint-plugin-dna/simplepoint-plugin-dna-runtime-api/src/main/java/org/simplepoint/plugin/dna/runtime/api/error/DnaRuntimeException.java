package org.simplepoint.plugin.dna.runtime.api.error;

import java.util.Objects;

/** Runtime failure with a stable code suitable for service boundaries. */
public class DnaRuntimeException extends RuntimeException {

  private final DnaRuntimeErrorCode errorCode;

  /** Creates a coded failure without an underlying cause. */
  public DnaRuntimeException(
      final DnaRuntimeErrorCode errorCode,
      final String message
  ) {
    super(message);
    this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
  }

  /** Creates a coded failure with an underlying cause retained for server diagnostics. */
  public DnaRuntimeException(
      final DnaRuntimeErrorCode errorCode,
      final String message,
      final Throwable cause
  ) {
    super(message, cause);
    this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
  }

  /** Returns the stable language-neutral error code. */
  public DnaRuntimeErrorCode errorCode() {
    return errorCode;
  }
}
