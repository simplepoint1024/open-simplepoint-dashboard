package org.simplepoint.security.resource;

import java.util.Objects;
import org.simplepoint.remoting.RemoteBusinessException;
import org.simplepoint.remoting.RemoteErrorDecoder;

/** Stable registration failures without reflecting submitted resource or permission values. */
public final class ResourceRegistrationException extends RemoteBusinessException {
  private static final String PREFIX = "RESOURCE_REGISTRATION:";
  private final Code code;

  /** Creates a canonical rejection suitable for either local or remote registration. */
  public ResourceRegistrationException(Code code) {
    super("Resource registration rejected: " + Objects.requireNonNull(code).name());
    this.code = code;
  }

  /** Returns the contract-owned rejection category. */
  public Code code() {
    return code;
  }

  @Override
  public String remoteErrorCode() {
    return PREFIX + code.name();
  }

  /** Returns a translation key with no submitted values as interpolation arguments. */
  public String messageKey() {
    return switch (code) {
      case ACCESS_DENIED -> "resources.registration.accessDenied";
      case CATALOG_CONTEXT_CONFLICT -> "resources.registration.catalogContextConflict";
      case CATALOG_UNAVAILABLE -> "resources.registration.catalogUnavailable";
      case DEPLOYMENT_CONFLICT -> "resources.registration.deploymentConflict";
      case GENERATION_REJECTED -> "resources.registration.generationRejected";
      case INVALID_DECLARATION -> "resources.registration.invalidDeclaration";
      case OWNERSHIP_CONFLICT -> "resources.registration.ownershipConflict";
      case IDENTITY_CONFLICT -> "resources.registration.identityConflict";
      case HIERARCHY_CONFLICT -> "resources.registration.hierarchyConflict";
    };
  }

  /** Rejections are explicit; an ownership migration must never be inferred from a collision. */
  public enum Code {
    ACCESS_DENIED,
    CATALOG_CONTEXT_CONFLICT,
    CATALOG_UNAVAILABLE,
    DEPLOYMENT_CONFLICT,
    GENERATION_REJECTED,
    INVALID_DECLARATION,
    OWNERSHIP_CONFLICT,
    IDENTITY_CONFLICT,
    HIERARCHY_CONFLICT
  }

  /** Accepts only known contract codes, ignoring remote diagnostic text. */
  public static final class Decoder implements RemoteErrorDecoder {
    @Override
    public RemoteBusinessException decode(String code, String message) {
      if (code == null || !code.startsWith(PREFIX)) {
        return null;
      }
      try {
        return new ResourceRegistrationException(Code.valueOf(code.substring(PREFIX.length())));
      } catch (IllegalArgumentException unknown) {
        return null;
      }
    }
  }
}
