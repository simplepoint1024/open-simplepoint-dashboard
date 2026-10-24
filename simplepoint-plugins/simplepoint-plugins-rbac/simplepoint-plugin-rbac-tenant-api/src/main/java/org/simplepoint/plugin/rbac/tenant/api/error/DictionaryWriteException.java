package org.simplepoint.plugin.rbac.tenant.api.error;

/** Stable dictionary mutation failures, without submitted values or database diagnostics. */
public final class DictionaryWriteException extends RuntimeException {
  private final Code code;

  /** Creates a public domain rejection; causes remain internal. */
  public DictionaryWriteException(Code code) {
    super("Dictionary write rejected: " + java.util.Objects.requireNonNull(code));
    this.code = code;
  }

  /** Returns the stable diagnostic category. */
  public Code code() {
    return code;
  }

  /** Returns the translated public error key without interpolation arguments. */
  public String messageKey() {
    return code == Code.CONTEXT_CONFLICT
        ? "dictionaries.write.contextConflict"
        : "dictionaries.write.unavailable";
  }

  /** Categories understood by the dictionary HTTP adapter. */
  public enum Code {
    CONTEXT_CONFLICT,
    UNAVAILABLE
  }
}
