package org.simplepoint.security.oauth2.resourceserver.composition;

import org.simplepoint.platform.composition.ModuleInitialization;
import org.simplepoint.platform.composition.ModuleInitializationContext;
import org.simplepoint.platform.composition.ModuleInitializer;
import org.simplepoint.security.context.AuthorizationContextService;

/** Binds shared request authorization to the HTTP owner's declared service client. */
@ModuleInitialization(consumes = AuthorizationContextService.class)
public final class ModuleAuthorizationInitializer implements ModuleInitializer {
  private final ModuleAuthorizationContexts contexts;

  /** Receives the framework registry; module clients come only from the bound context. */
  public ModuleAuthorizationInitializer(ModuleAuthorizationContexts contexts) {
    this.contexts = contexts;
  }

  @Override
  public Runnable prepare(ModuleInitializationContext context) {
    contexts.bind(context.moduleId(), context.client(AuthorizationContextService.class));
    return () -> {};
  }
}
