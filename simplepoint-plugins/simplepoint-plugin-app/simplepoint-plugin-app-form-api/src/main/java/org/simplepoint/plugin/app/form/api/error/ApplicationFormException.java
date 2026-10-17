package org.simplepoint.plugin.app.form.api.error;

import java.util.Objects;

/** Form-domain failure that does not expose persistence or downstream internals. */
public class ApplicationFormException extends org.simplepoint.remoting.RemoteBusinessException {

  private final ApplicationFormErrorCode errorCode;

  /** Creates a stable form-domain failure. */
  public ApplicationFormException(final ApplicationFormErrorCode errorCode, final String message) {
    super(message);
    this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
  }

  /** Creates a stable form-domain failure while retaining the private cause. */
  public ApplicationFormException(
      final ApplicationFormErrorCode errorCode, final String message, final Throwable cause) {
    super(message, cause);
    this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
  }

  @Override
  public String remoteErrorCode() {
    return "APP_FORM:" + errorCode.name();
  }

  /** Returns the transport-safe failure identity. */
  public ApplicationFormErrorCode errorCode() {
    return errorCode;
  }
}
