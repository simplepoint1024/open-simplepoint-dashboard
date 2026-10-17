package org.simplepoint.plugin.dna.runtime.service.remote;

import java.util.Set;
import org.simplepoint.plugin.dna.runtime.configuration.DnaFormRuntimeRemoteProperties;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Rejects forged form invocation context from service clients outside the APP allowlist. */
@Component
public class DnaFormRuntimeCallerGuard {

  private final Set<String> allowedCallers;

  /** Freezes the configured caller allowlist at startup. */
  public DnaFormRuntimeCallerGuard(final DnaFormRuntimeRemoteProperties properties) {
    this.allowedCallers = properties.getAllowedCallers().stream()
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  /** Requires the current OAuth2 service principal to match the configured APP client. */
  public void requireTrustedCaller() {
    if (!org.simplepoint.core.security.ServiceClientIdentity.allowed(
        SecurityContextHolder.getContext().getAuthentication(), allowedCallers)) {
      throw new AccessDeniedException("Caller cannot invoke the DNA form runtime contract");
    }
  }
}
