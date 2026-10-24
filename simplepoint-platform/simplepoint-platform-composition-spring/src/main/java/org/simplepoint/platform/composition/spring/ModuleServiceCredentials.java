package org.simplepoint.platform.composition.spring;

import com.simplepoint.service.router.invocation.LocalInvocationContext;
import com.simplepoint.service.router.invocation.ServiceRouterAccessTokenProvider;
import java.util.Map;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.springframework.core.Ordered;

/**
 * Selects independently cached service credentials bound by module client injection. Neither
 * request attributes nor caller-supplied identity strings can select a module's credentials.
 */
public final class ModuleServiceCredentials
    implements ServiceRouterAccessTokenProvider, LocalInvocationContext, Ordered {
  private final Map<String, ServiceRouterAccessTokenProvider> providers;
  private final ThreadLocal<ServiceRouterAccessTokenProvider> selected = new ThreadLocal<>();

  /** Freezes the configured providers; binding is restricted to the module client factory. */
  public ModuleServiceCredentials(Map<String, ServiceRouterAccessTokenProvider> providers) {
    this.providers = Map.copyOf(providers);
  }

  Scope bind(String module) {
    var provider = providers.get(module);
    if (provider == null) {
      throw new ModuleCompositionException("MISSING_MODULE_CREDENTIALS", module);
    }
    return bind(provider);
  }

  private Scope bind(ServiceRouterAccessTokenProvider provider) {
    var previous = selected.get();
    selected.set(provider);
    return () -> {
      if (previous == null) {
        selected.remove();
      } else {
        selected.set(previous);
      }
    };
  }

  @Override
  public String getAccessToken() {
    return requireSelection().getAccessToken();
  }

  @Override
  public void invalidate(String rejectedToken) {
    requireSelection().invalidate(rejectedToken);
  }

  @Override
  public LocalInvocationContext prepare() {
    var provider = requireSelection();
    return () -> bind(provider);
  }

  @Override
  public Scope open() {
    throw new IllegalStateException("Module invocation context must be prepared on the caller");
  }

  private ServiceRouterAccessTokenProvider requireSelection() {
    var provider = selected.get();
    if (provider == null) {
      throw new ModuleCompositionException(
          "UNBOUND_MODULE_CALLER", "Use an injected module client");
    }
    return provider;
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }
}
