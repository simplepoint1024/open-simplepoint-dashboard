package org.simplepoint.plugin.dna.federation.api.error;

/** A safe public diagnosis; neither submitted SQL nor parser excerpts are exposed. */
public final class FederationSqlWriteException extends RuntimeException {
  private final Code code;

  /** Creates a language-neutral rejection with a fixed diagnostic, never submitted SQL. */
  public FederationSqlWriteException(Code code) {
    super(
        switch (java.util.Objects.requireNonNull(code, "code")) {
          case SQL_WRITE_UNSUPPORTED ->
              "SQL write cannot be completely analyzed by the supported grammar";
          case SQL_WRITE_SCOPE_INVALID -> "SQL write must resolve to one unambiguous datasource";
          case SQL_WRITE_PROTECTED -> "SQL cannot directly modify a managed physical object";
          case SQL_WRITE_OWNERSHIP_UNAVAILABLE -> "Physical write ownership could not be verified";
        });
    this.code = java.util.Objects.requireNonNull(code, "code");
  }

  /** Returns the stable machine-readable rejection category. */
  public Code errorCode() {
    return code;
  }

  /** Returns the platform translation key for this rejection. */
  public String messageKey() {
    return "dna.federation.sql-console.error." + code.name();
  }

  /** Supported admission failure categories. */
  public enum Code {
    SQL_WRITE_UNSUPPORTED,
    SQL_WRITE_SCOPE_INVALID,
    SQL_WRITE_PROTECTED,
    SQL_WRITE_OWNERSHIP_UNAVAILABLE
  }
}
