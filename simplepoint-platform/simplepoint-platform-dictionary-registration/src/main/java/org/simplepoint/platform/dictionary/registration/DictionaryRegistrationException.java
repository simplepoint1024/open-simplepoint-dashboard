package org.simplepoint.platform.dictionary.registration;

import org.simplepoint.remoting.RemoteBusinessException;
import org.simplepoint.remoting.RemoteErrorDecoder;

/** Safe domain rejections shared by local isolation and remote transports. */
public final class DictionaryRegistrationException extends RemoteBusinessException {
  private static final String PREFIX = "DICTIONARY_REGISTRATION:";
  private final Code code;

  /** Creates a rejection without any caller-supplied values in its message. */
  public DictionaryRegistrationException(Code code) {
    super("Dictionary registration rejected: " + java.util.Objects.requireNonNull(code));
    this.code = code;
  }

  /** Returns the stable diagnostic category. */
  public Code code() {
    return code;
  }

  @Override
  public String remoteErrorCode() {
    return PREFIX + code;
  }

  /** Returns a namespaced translation key with no submitted values as parameters. */
  public String messageKey() {
    return switch (code) {
      case ACCESS_DENIED -> "dictionaries.registration.accessDenied";
      case NOT_APPROVED -> "dictionaries.registration.notApproved";
      case INVALID_DECLARATION -> "dictionaries.registration.invalidDeclaration";
      case DECLARATION_MISMATCH -> "dictionaries.registration.declarationMismatch";
      case OWNERSHIP_CONFLICT -> "dictionaries.registration.ownershipConflict";
      case LEGACY_ADOPTION_REQUIRED -> "dictionaries.registration.legacyAdoptionRequired";
      case DEPLOYMENT_CONFLICT -> "dictionaries.registration.deploymentConflict";
      case CATALOG_CONTEXT_CONFLICT -> "dictionaries.registration.catalogContextConflict";
      case CATALOG_UNAVAILABLE -> "dictionaries.registration.catalogUnavailable";
    };
  }

  /** Explicit refusal categories; registration never silently claims existing unmanaged data. */
  public enum Code {
    ACCESS_DENIED,
    NOT_APPROVED,
    INVALID_DECLARATION,
    DECLARATION_MISMATCH,
    OWNERSHIP_CONFLICT,
    LEGACY_ADOPTION_REQUIRED,
    DEPLOYMENT_CONFLICT,
    CATALOG_CONTEXT_CONFLICT,
    CATALOG_UNAVAILABLE
  }

  /** Restores only known contract errors, ignoring remote diagnostic text. */
  public static final class Decoder implements RemoteErrorDecoder {
    @Override
    public RemoteBusinessException decode(String code, String message) {
      if (code == null || !code.startsWith(PREFIX)) {
        return null;
      }
      try {
        return new DictionaryRegistrationException(Code.valueOf(code.substring(PREFIX.length())));
      } catch (IllegalArgumentException unknown) {
        return null;
      }
    }
  }
}
