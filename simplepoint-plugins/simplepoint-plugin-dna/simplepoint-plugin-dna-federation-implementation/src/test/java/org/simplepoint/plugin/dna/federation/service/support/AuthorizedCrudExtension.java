package org.simplepoint.plugin.dna.federation.service.support;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;

/** Explicit authenticated scope for CRUD domain tests; never leaks into the next test. */
public final class AuthorizedCrudExtension implements BeforeEachCallback, AfterEachCallback {
  @Override
  public void beforeEach(ExtensionContext extension) {
    var authorization = new AuthorizationContext();
    authorization.setUserId("dna-test-user");
    authorization.setDataScopeType("ALL");
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, authorization);
  }

  @Override
  public void afterEach(ExtensionContext extension) {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }
}
