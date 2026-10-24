package org.simplepoint.platform.installation;

/** Fixed diagnostics; neither credentials, step payloads nor provider exception text is exposed. */
public final class InstallationException extends RuntimeException {
  private final Code code;

  /** Creates a stable failure without interpolating provider input. */
  public InstallationException(Code code) {
    super("INSTALLATION_" + java.util.Objects.requireNonNull(code));
    this.code = code;
  }

  /** Returns the machine-readable category. */
  public Code code() {
    return code;
  }

  /** Journal and orchestration failures remain distinct from domain business errors. */
  public enum Code {
    PLAN_CONFLICT,
    GENERATION_CONFLICT,
    OPERATION_IN_PROGRESS,
    STEP_CONFLICT,
    RECOVERY_REQUIRED,
    ACTION_FAILED,
    RECONCILIATION_FAILED,
    ADAPTER_MISMATCH,
    JOURNAL_UNAVAILABLE
  }
}
