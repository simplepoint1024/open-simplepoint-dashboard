package org.simplepoint.plugin.rbac.resource.service.support;

import java.util.Map;
import org.simplepoint.core.security.ServiceClientIdentity;
import org.simplepoint.plugin.rbac.resource.service.properties.ResourceRegistrationProperties;
import org.simplepoint.security.resource.ResourceRegistrationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Freezes the operator's exact owner/client policy; absent mappings never infer trust. */
@Component
public final class ResourceRegistrationCallerPolicy {
  private final Map<String, String> ownerClients;

  /** Does not derive authorization from discovered beans, caller input or a host credential. */
  public ResourceRegistrationCallerPolicy(ResourceRegistrationProperties properties) {
    var configured = properties.getOwnerClients();
    configured.forEach(
        (owner, client) -> {
          validate(owner, 128);
          validate(client, 256);
        });
    ownerClients = Map.copyOf(configured);
  }

  /** The authentication must already have been established by the platform security boundary. */
  public void requireCaller(String owner) {
    requireConfiguredCaller(owner, authenticatedCaller());
  }

  /**
   * Extracts only an authenticated OAuth service identity; owner authority is checked in storage.
   */
  public String authenticatedCaller() {
    String client =
        ServiceClientIdentity.authenticatedClientId(
                SecurityContextHolder.getContext().getAuthentication())
            .orElse(null);
    try {
      validate(client, 256);
    } catch (IllegalArgumentException invalid) {
      throw new ResourceRegistrationException(ResourceRegistrationException.Code.ACCESS_DENIED);
    }
    return client;
  }

  /** Exact compatibility policy, used only when the owner has never entered managed deployment. */
  public void requireConfiguredCaller(String owner, String verifiedClient) {
    String expected = owner == null ? null : ownerClients.get(owner);
    if (expected == null || !expected.equals(verifiedClient)) {
      throw new ResourceRegistrationException(ResourceRegistrationException.Code.ACCESS_DENIED);
    }
  }

  private static void validate(String value, int maximum) {
    if (value == null
        || value.isBlank()
        || value.length() > maximum
        || value
            .codePoints()
            .anyMatch(
                character ->
                    Character.isWhitespace(character)
                        || Character.getType(character) == Character.SURROGATE
                        || Character.isISOControl(character)
                        || character == '*')) {
      throw new IllegalArgumentException("Invalid resource registration owner/client policy");
    }
  }
}
