package org.simplepoint.plugin.rbac.tenant.service.impl;

import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.rbac.tenant.service.properties.ManagedTenantResourceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Resolves the immutable managed-resource namespace from the authenticated service client. */
@Component
public class ManagedTenantResourceCallerGuard {

  private static final Logger LOGGER = LoggerFactory.getLogger(ManagedTenantResourceCallerGuard.class);
  private final Map<String, String> callerOwners;

  /** Freezes the configured service namespaces after configuration binding. */
  public ManagedTenantResourceCallerGuard(
      final ManagedTenantResourceProperties properties
  ) {
    this.callerOwners = properties.getCallerOwners().entrySet().stream()
        .filter(entry -> entry.getKey() != null && entry.getValue() != null)
        .map(entry -> Map.entry(entry.getKey().trim(), entry.getValue().trim()))
        .filter(entry -> !entry.getKey().isEmpty() && !entry.getValue().isEmpty())
        .collect(java.util.stream.Collectors.toUnmodifiableMap(
            Map.Entry::getKey,
            Map.Entry::getValue
        ));
  }

  /** Returns the namespace owned by the current service client, failing closed by default. */
  public String requireOwner() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    for (var entry : callerOwners.entrySet()) {
      if (org.simplepoint.core.security.ServiceClientIdentity.allowed(authentication, Set.of(entry.getKey()))) {
        return entry.getValue();
      }
    }
    LOGGER.warn("Rejected managed resource caller: authenticated={}, name={}, configuredOwners={}",
        authentication != null && authentication.isAuthenticated(), authentication == null ? null : authentication.getName(), callerOwners.keySet());
    throw new AccessDeniedException("Caller cannot manage tenant resources");
  }

}
