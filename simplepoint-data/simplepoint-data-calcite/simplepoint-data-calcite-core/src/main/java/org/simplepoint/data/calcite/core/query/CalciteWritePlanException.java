package org.simplepoint.data.calcite.core.query;

/** Safe library diagnostics; domain adapters map these to their own public error contracts. */
public final class CalciteWritePlanException extends IllegalStateException {
  private final Code code;

  /** Creates a diagnosis without submitted SQL, physical names or datasource details. */
  public CalciteWritePlanException(Code code) {
    super("Calcite write plan verification failed: " + java.util.Objects.requireNonNull(code));
    this.code = code;
  }

  /** Returns the stable reason independently of the display text. */
  public Code code() {
    return code;
  }

  /** Incomplete or unsupported binding cannot be treated as an admitted write. */
  public enum Code {
    MISSING_PLAN,
    MULTIPLE_PLANS,
    UNSUPPORTED_PLAN,
    PLAN_LIMIT_EXCEEDED
  }
}
