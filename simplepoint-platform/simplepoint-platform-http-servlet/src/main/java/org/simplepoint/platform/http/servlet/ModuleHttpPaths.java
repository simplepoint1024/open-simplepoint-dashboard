package org.simplepoint.platform.http.servlet;

import java.util.Objects;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.platform.composition.ModuleHttpPlan;
import org.simplepoint.platform.composition.ModulePlan;

/** Shares MVC mounting identity with security filters and non-MVC transport adapters. */
public final class ModuleHttpPaths {
  private ModuleHttpPaths() {}

  /**
   * Returns the selected adapter's prefix, or the legacy host's already stripped namespace. A
   * modular host never falls back when an adapter is unowned or its module has no HTTP contract.
   */
  public static String prefix(ModulePlan modules, Class<?> adapter) {
    Objects.requireNonNull(adapter, "adapter");
    if (modules == null) {
      return "";
    }
    return ModuleHttpPlan.from(modules)
        .namespaceOf(adapter)
        .map(prefix -> prefix.equals("/") ? "" : prefix)
        .orElseThrow(
            () -> new ModuleCompositionException("UNOWNED_HTTP_ADAPTER", adapter.getName()));
  }

  /** Selected bounded public roots for security filters sharing the MVC routing boundary. */
  public static java.util.Set<String> roots(ModulePlan modules, Class<?> adapter) {
    java.util.Objects.requireNonNull(modules, "modules");
    java.util.Objects.requireNonNull(adapter, "adapter");
    var http = ModuleHttpPlan.from(modules);
    return http.ownerOf(adapter)
        .map(http.routes()::get)
        .orElseThrow(
            () -> new ModuleCompositionException("UNOWNED_HTTP_ADAPTER", adapter.getName()));
  }
}
