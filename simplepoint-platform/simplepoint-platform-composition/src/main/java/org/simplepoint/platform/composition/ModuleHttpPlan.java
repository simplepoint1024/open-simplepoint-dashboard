package org.simplepoint.platform.composition;

import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Validates HTTP ownership without constructing controllers or opening connections. */
public final class ModuleHttpPlan {
  private final ModulePlan modules;
  private final Map<String, String> namespaces;
  private final Map<String, Set<String>> routes;

  private ModuleHttpPlan(
      ModulePlan modules, Map<String, String> namespaces, Map<String, Set<String>> routes) {
    this.modules = modules;
    this.namespaces = Collections.unmodifiableMap(new TreeMap<>(namespaces));
    this.routes = Collections.unmodifiableMap(new TreeMap<>(routes));
  }

  /** Derives selected route ownership; overlaps are never resolved by registration order. */
  public static ModuleHttpPlan from(ModulePlan modules) {
    var namespaces = new TreeMap<String, String>();
    var routes = new TreeMap<String, Set<String>>();
    for (var module : modules.modules()) {
      var http = module.configuration().getDeclaredAnnotation(ModuleHttp.class);
      if (http == null) {
        continue;
      }
      String path = http.namespace();
      if (path.length() > 128
          || !(path.equals("/") || path.matches("(?:/[a-z][a-z0-9-]*)+"))
          || path.equals("/") && http.routes() == ModuleHttpRoutes.class) {
        throw new ModuleCompositionException("INVALID_HTTP_NAMESPACE", module.id() + ": " + path);
      }
      Set<String> roots = roots(module, http);
      for (var previous : routes.entrySet()) {
        for (String left : previous.getValue()) {
          for (String right : roots) {
            if (contains(left, right) || contains(right, left)) {
              throw new ModuleCompositionException(
                  "CONFLICTING_HTTP_NAMESPACE",
                  previous.getKey() + ": " + left + ", " + module.id() + ": " + right);
            }
          }
        }
      }
      namespaces.put(module.id(), path);
      routes.put(module.id(), roots);
    }
    return new ModuleHttpPlan(modules, namespaces, routes);
  }

  private static Set<String> roots(ModuleDescriptor module, ModuleHttp http) {
    if (http.routes() == ModuleHttpRoutes.class) {
      return Set.of(http.namespace());
    }
    Set<String> declared;
    try {
      declared = http.routes().getConstructor().newInstance().roots(module);
    } catch (ReflectiveOperationException failure) {
      if (failure instanceof InvocationTargetException invocation
          && invocation.getCause() instanceof ModuleCompositionException composition) {
        throw composition;
      }
      throw new ModuleCompositionException("MODULE_HTTP_ROUTES_FAILED", module.id());
    }
    if (declared == null || declared.isEmpty() || declared.size() > 256) {
      throw new ModuleCompositionException("INVALID_MODULE_HTTP_ROUTES", module.id());
    }
    var roots = new TreeSet<String>();
    for (String path : declared) {
      if (path == null
          || path.length() > 256
          || !path.matches("(?:/(?:[a-zA-Z0-9_~][a-zA-Z0-9_.~-]*|\\.well-known))+")) {
        throw new ModuleCompositionException("INVALID_MODULE_HTTP_ROUTES", module.id());
      }
      roots.add(http.namespace().equals("/") ? path : http.namespace() + path);
    }
    var minimal = new TreeSet<String>();
    for (String root : roots) {
      if (minimal.stream().noneMatch(parent -> contains(parent, root))) {
        minimal.add(root);
      }
    }
    return Collections.unmodifiableSet(minimal);
  }

  /** Returns module prefixes for servlet mounting; shared prefixes may occur more than once. */
  public Map<String, String> namespaces() {
    return namespaces;
  }

  /** Returns disjoint public subtrees for gateway routing and runtime ownership checks. */
  public Map<String, Set<String>> routes() {
    return routes;
  }

  /** Resolves only classes owned by the selected module packages. */
  public Optional<String> namespaceOf(Class<?> type) {
    return ownerOf(type).map(namespaces::get);
  }

  /** Returns selected ownership even when the module has not declared an HTTP contribution. */
  public Optional<String> ownerOf(Class<?> type) {
    return modules
        .ownerOf(type)
        .filter(module -> type.getClassLoader() == module.configuration().getClassLoader())
        .map(ModuleDescriptor::id);
  }

  /** Segment-boundary containment, so /work never owns /workflow. */
  public static boolean contains(String namespace, String path) {
    return path.equals(namespace) || path.startsWith(namespace + "/");
  }
}
