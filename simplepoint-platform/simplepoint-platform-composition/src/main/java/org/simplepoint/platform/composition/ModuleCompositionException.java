package org.simplepoint.platform.composition;

/** Configuration failure before modules are activated; never contains environment credentials. */
public final class ModuleCompositionException extends IllegalArgumentException {

  private final String code;

  /**
   * Creates a composition diagnostic.
   *
   * @param code stable diagnostic identifier
   * @param detail module or contract identities explaining the failure
   */
  public ModuleCompositionException(final String code, final String detail) {
    super(code + ": " + detail);
    this.code = code;
  }

  /** Returns the stable diagnostic identifier. */
  public String code() {
    return code;
  }
}
