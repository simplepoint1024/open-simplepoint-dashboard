package org.simplepoint.calculation;

/** Stable, value-free diagnostics usable by a CLI, HTTP adapter or editor. */
public final class FormulaException extends IllegalArgumentException {
  private final String code;
  private final int line;
  private final int column;

  /** Creates a stable diagnostic without submitted values. */
  public FormulaException(String code) {
    this(code, 1, 0);
  }

  /** Creates a stable diagnostic with a source position. */
  public FormulaException(String code, int line, int column) {
    super(code);
    this.code = code;
    this.line = line;
    this.column = column;
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
