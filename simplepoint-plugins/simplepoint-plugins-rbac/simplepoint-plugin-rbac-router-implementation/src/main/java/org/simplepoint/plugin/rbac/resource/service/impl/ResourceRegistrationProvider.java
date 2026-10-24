package org.simplepoint.plugin.rbac.resource.service.impl;

import java.util.Set;
import org.simplepoint.plugin.rbac.resource.service.support.ResourceRegistrationCallerPolicy;
import org.simplepoint.remoting.RemoteProvider;
import org.simplepoint.security.ResourceDeclaration;
import org.simplepoint.security.service.ResourceRegistrationService;
import org.simplepoint.security.service.ResourceService;
import org.springframework.stereotype.Service;

/** Authenticates routed declarations before entering the identity-owned mutation transaction. */
@Service
@RemoteProvider
public class ResourceRegistrationProvider implements ResourceRegistrationService {
  private final ResourceService resources;
  private final ResourceRegistrationCallerPolicy policy;

  /** Reuses local domain logic without exporting its resource-management methods. */
  public ResourceRegistrationProvider(
      ResourceService resources, ResourceRegistrationCallerPolicy policy) {
    this.resources = resources;
    this.policy = policy;
  }

  @Override
  public void sync(String owner, Set<ResourceDeclaration> declarations) {
    resources.syncRegistered(owner, declarations, policy.authenticatedCaller());
  }
}
