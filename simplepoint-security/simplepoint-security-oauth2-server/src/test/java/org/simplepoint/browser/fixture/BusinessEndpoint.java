package org.simplepoint.browser.fixture;

import org.simplepoint.core.AuthorizationContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Business fixture with distinct read/write permissions. */
@RestController
public class BusinessEndpoint {
  /** Reads the module-scoped identity only with the declared permission. */
  @GetMapping("/records")
  @PreAuthorize("hasAuthority('records.read')")
  public String read() {
    return AuthorizationContextHolder.getContext().getUserId();
  }

  /** Writes require a separate permission and browser CSRF protection. */
  @PostMapping("/records")
  @PreAuthorize("hasAuthority('records.write')")
  public String write() {
    return AuthorizationContextHolder.getContext().getUserId();
  }
}
