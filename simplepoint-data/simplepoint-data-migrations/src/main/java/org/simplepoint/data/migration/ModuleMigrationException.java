package org.simplepoint.data.migration;

/** Stable migration failure context; connection strings and SQL do not enter its public message. */
public final class ModuleMigrationException extends RuntimeException {
  private final String code;

  /** Retains the diagnostic cause separately from the stable module-scoped message. */
  public ModuleMigrationException(String code, String module, Throwable cause) {
    super(code + ": " + module, cause);
    this.code = code;
  }

  /** Returns the stable failure category. */
  public String code() {
    return code;
  }
}
