package org.simplepoint.data.ownership;

import java.sql.SQLException;

/** Stable infrastructure diagnostics; adapters map these codes into their public error envelope. */
public final class PhysicalOwnershipException extends SQLException {
  private final Code code;

  /** Creates a rejection without physical names, submitted SQL or owner identities. */
  public PhysicalOwnershipException(Code code) {
    super(
        "Physical ownership operation rejected: " + java.util.Objects.requireNonNull(code),
        "55000");
    this.code = code;
  }

  /** Returns the diagnostic code independently of the exception's display text. */
  public Code code() {
    return code;
  }

  /** Infrastructure states, including explicit installation and legacy adoption requirements. */
  public enum Code {
    UNSUPPORTED_DATABASE,
    NAMESPACE_UNAVAILABLE,
    METADATA_UNAVAILABLE,
    INVALID_OBJECT_NAME,
    SESSION_BUSY,
    SESSION_CONFIGURATION_UNSUPPORTED,
    SESSION_STATE_INVALID,
    DIRECTORY_NOT_INSTALLED,
    NAMESPACE_NOT_ACTIVATED,
    DIRECTORY_INVALID,
    INSTALLATION_CONFLICT,
    OWNER_CONFLICT,
    EXISTING_OBJECT_REQUIRES_ADOPTION,
    RESERVED_OBJECT
  }
}
