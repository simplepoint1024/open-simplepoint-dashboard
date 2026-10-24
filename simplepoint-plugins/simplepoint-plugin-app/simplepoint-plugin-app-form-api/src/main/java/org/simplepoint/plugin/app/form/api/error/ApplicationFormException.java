package org.simplepoint.plugin.app.form.api.error;

import java.util.Objects;
import org.simplepoint.plugin.app.form.api.model.FormCalculationDiagnostic;

/** Form-domain failure that does not expose persistence or downstream internals. */
public class ApplicationFormException extends org.simplepoint.remoting.RemoteBusinessException {

  private final ApplicationFormErrorCode errorCode;
  private final FormCalculationDiagnostic calculation;

  /** Creates a stable form-domain failure. */
  public ApplicationFormException(final ApplicationFormErrorCode errorCode, final String message) {
    super(message);
    this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    this.calculation = null;
  }

  /** Creates a stable form-domain failure while retaining the private cause. */
  public ApplicationFormException(
      final ApplicationFormErrorCode errorCode, final String message, final Throwable cause) {
    this(errorCode, message, cause, null);
  }

  /** Retains structured, value-free calculation context for local HTTP adapters. */
  public ApplicationFormException(
      final ApplicationFormErrorCode errorCode,
      final String message,
      final Throwable cause,
      final FormCalculationDiagnostic calculation) {
    super(message, cause);
    this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    this.calculation = calculation;
  }

  /** Returns optional safe calculation context, independently of the private exception chain. */
  public FormCalculationDiagnostic calculation() {
    return calculation;
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
