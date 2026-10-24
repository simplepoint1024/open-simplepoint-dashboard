package org.simplepoint.plugin.rbac.core.api.authentication;

import org.simplepoint.remoting.RemoteBusinessException;
import org.simplepoint.remoting.RemoteErrorDecoder;

/** Client-safe credential failures with a stable namespace and no submitted values. */
public final class CredentialAuthenticationException extends RemoteBusinessException {
  private final Code code;

  /** Creates a canonical failure; callers cannot inject arbitrary public diagnostic text. */
  public CredentialAuthenticationException(Code code) {
    super(
        code == Code.UNAVAILABLE
            ? "Credential verification unavailable"
            : "Credential verification failed");
    this.code = java.util.Objects.requireNonNull(code);
  }

  @Override
  public String remoteErrorCode() {
    return "IDENTITY_CREDENTIAL:" + code.name();
  }

  /** Returns the platform translation key without interpolation of credential input. */
  public String messageKey() {
    return switch (code) {
      case INVALID -> "identity.credentials.invalid";
      case UNAVAILABLE -> "identity.credentials.unavailable";
      case SESSION_INVALID -> "identity.credentials.sessionInvalid";
    };
  }

  /** Returns the contract-owned failure category. */
  public Code code() {
    return code;
  }

  /** Account existence, password correctness and individual factor failures share INVALID. */
  public enum Code {
    INVALID,
    UNAVAILABLE,
    SESSION_INVALID
  }

  /** Decodes only this contract's known codes and ignores remote diagnostic text. */
  public static final class Decoder implements RemoteErrorDecoder {
    @Override
    public RemoteBusinessException decode(String value, String message) {
      if (value == null || !value.startsWith("IDENTITY_CREDENTIAL:")) {
        return null;
      }
      try {
        return new CredentialAuthenticationException(
            Code.valueOf(value.substring("IDENTITY_CREDENTIAL:".length())));
      } catch (IllegalArgumentException invalid) {
        return null;
      }
    }
  }
}
