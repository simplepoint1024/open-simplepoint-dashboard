package org.simplepoint.calculation;

/** Stable, value-free diagnostics usable by a CLI, HTTP adapter or editor. */
public final class FormulaException extends IllegalArgumentException {
  private final String code;
  private final int line;
  private final int column;
  private final String scope;
  private final String field;
  private final Integer row;

  /** Creates a stable diagnostic without submitted values. */
  public FormulaException(String code) {
    this(code, 1, 0);
  }

  /** Creates a stable diagnostic with a source position. */
  public FormulaException(String code, int line, int column) {
    this(code, line, column, null, null, null);
  }

  private FormulaException(
      String code, int line, int column, String scope, String field, Integer row) {
    super(code);
    this.code = code;
    this.line = line;
    this.column = column;
    this.scope = scope;
    this.field = field;
    this.row = row;
  }

  /**
   * Adds stable aggregate identities, never values; nested diagnostics retain their original owner.
   */
  public FormulaException atField(String scope, String field, Integer row) {
    return this.field == null ? new FormulaException(code, line, column, scope, field, row) : this;
  }

  /** Returns the aggregate scope, when the caller supplied one. */
  public String scope() {
    return scope;
  }

  /** Returns the stable field identity, when known. */
  public String field() {
    return field;
  }

  /** Returns the zero-based detail row index, or null for parent/compile diagnostics. */
  public Integer row() {
    return row;
  }

  /** Returns the stable host-translatable diagnostic code. */
  public String code() {
    return code;
  }

  /** Returns the one-based source line. */
  public int line() {
    return line;
  }

  /** Returns the zero-based source column. */
  public int column() {
    return column;
  }
}
