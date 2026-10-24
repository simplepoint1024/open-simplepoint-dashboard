package org.simplepoint.platform.composition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Discovers trusted module metadata and computes deterministic local assembly plans. */
public final class ModuleCatalog {

  /** Classpath index containing one annotated configuration class per line. */
  public static final String INDEX = "META-INF/simplepoint/modules.imports";

  private static final int MAX_MODULES = 256;

  private final Map<String, ModuleDescriptor> modules;

  /**
   * Reads declarations without constructing configurations.
   *
   * @param configurations available module configuration types
   */
  public ModuleCatalog(final Collection<Class<?>> configurations) {
    var result = new TreeMap<String, ModuleDescriptor>();
    if (configurations.size() > MAX_MODULES) {
      throw failure("MODULE_LIMIT", Integer.toString(MAX_MODULES));
    }
    for (Class<?> type : configurations) {
      ModuleDescriptor module = ModuleDescriptor.from(type);
      ModuleDescriptor previous = result.putIfAbsent(module.id(), module);
      if (previous != null) {
        throw failure(
            "DUPLICATE_MODULE",
            module.id() + ": " + previous.configuration().getName() + ", " + type.getName());
      }
    }
    validatePackages(List.copyOf(result.values()));
    modules = Collections.unmodifiableMap(result);
  }

  /**
   * Loads all module indexes from a trusted application class loader.
   *
   * @param loader application class loader
   * @return catalog without initializing configuration classes
   */
  public static ModuleCatalog discover(final ClassLoader loader) {
    var configurations = new ArrayList<Class<?>>();
    try {
      var indexes = Collections.list(loader.getResources(INDEX));
      indexes.sort(java.util.Comparator.comparing(java.net.URL::toExternalForm));
      for (var index : indexes) {
        String content;
        try (var input = index.openStream()) {
          byte[] bytes = input.readNBytes(65_537);
          if (bytes.length > 65_536) {
            throw failure("INDEX_LIMIT", INDEX);
          }
          content = new String(bytes, StandardCharsets.UTF_8);
        }
        for (String line : content.lines().toList()) {
          String name = line.split("#", 2)[0].strip();
          if (!name.isEmpty()) {
            if (configurations.size() == MAX_MODULES) {
              throw failure("MODULE_LIMIT", Integer.toString(MAX_MODULES));
            }
            configurations.add(Class.forName(name, false, loader));
          }
        }
      }
      return new ModuleCatalog(configurations);
    } catch (IOException | ClassNotFoundException | LinkageError ex) {
      throw failure("MODULE_LOAD_FAILED", ex.getClass().getSimpleName());
    }
  }

  /** Returns available modules in stable ID order. */
  public Collection<ModuleDescriptor> modules() {
    return modules.values();
  }

  /**
   * Selects roots and their required local dependencies; no implicit selection of other modules.
   *
   * @param roots logical module IDs selected by the deployment
   * @return validated assembly plan
   */
  public ModulePlan plan(final Collection<String> roots) {
    var ordered = new LinkedHashMap<String, ModuleDescriptor>();
    for (String root : new TreeSet<>(roots)) {
      visit(root, new ArrayList<>(), ordered);
    }
    List<ModuleDescriptor> selected = List.copyOf(ordered.values());
    var owners = new TreeMap<String, String>();
    var contracts = new TreeMap<String, Class<?>>();
    for (ModuleDescriptor module : selected) {
      module
          .provides()
          .forEach(
              (key, type) -> {
                String previous = owners.putIfAbsent(key, module.id());
                if (previous != null) {
                  throw failure("AMBIGUOUS_CAPABILITY", key + ": " + previous + ", " + module.id());
                }
                contracts.put(key, type);
              });
    }
    var remote = new TreeMap<String, Class<?>>();
    for (ModuleDescriptor module : selected) {
      module
          .consumes()
          .forEach(
              (key, type) -> {
                Class<?> actual = contracts.get(key);
                if (actual == null) {
                  actual = remote.putIfAbsent(key, type);
                }
                if (actual != null && actual != type) {
                  throw failure(
                      "INCOMPATIBLE_CAPABILITY_TYPE",
                      key + ": " + actual.getName() + ", " + type.getName());
                }
              });
    }
    var plan = new ModulePlan(selected, owners, remote);
    ModuleHttpPlan.from(plan);
    ModuleResourcePlan.owners(plan);
    ModuleDictionaryPlan.declarations(plan);
    return plan;
  }

  /**
   * Validates a complete deployment and derives routes without hand-maintained capability names. A
   * physical service may have many replicas but each logical module has one owning service.
   *
   * @param assignments physical service IDs mapped to selected root modules
   * @return complete deterministic deployment
   */
  public DeploymentPlan deploy(final Map<String, ? extends Collection<String>> assignments) {
    if (assignments.isEmpty() || assignments.size() > MAX_MODULES) {
      throw failure("INVALID_HOST_COUNT", Integer.toString(assignments.size()));
    }
    var hosts = new TreeMap<String, ModulePlan>();
    var moduleHosts = new TreeMap<String, String>();
    var capabilityHosts = new TreeMap<String, String>();
    var contracts = new TreeMap<String, Class<?>>();
    new TreeMap<>(assignments)
        .forEach(
            (host, roots) -> {
              if (!host.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}") || roots.isEmpty()) {
                throw failure("INVALID_HOST", host);
              }
              ModulePlan plan = plan(roots);
              hosts.put(host, plan);
              for (ModuleDescriptor module : plan.modules()) {
                String previousHost = moduleHosts.putIfAbsent(module.id(), host);
                if (previousHost != null) {
                  throw failure(
                      "DUPLICATE_MODULE_HOST", module.id() + ": " + previousHost + ", " + host);
                }
                module
                    .provides()
                    .forEach(
                        (key, type) -> {
                          String previous = capabilityHosts.putIfAbsent(key, host);
                          if (previous != null) {
                            throw failure(
                                "AMBIGUOUS_CAPABILITY", key + ": " + previous + ", " + host);
                          }
                          contracts.put(key, type);
                        });
              }
            });
    hosts.forEach(
        (host, plan) ->
            plan.remoteCapabilities()
                .forEach(
                    (key, type) -> {
                      Class<?> provider = contracts.get(key);
                      if (provider == null) {
                        throw failure("MISSING_CAPABILITY", host + " -> " + key);
                      }
                      if (provider != type) {
                        throw failure(
                            "INCOMPATIBLE_CAPABILITY_TYPE",
                            key + ": " + provider.getName() + ", " + type.getName());
                      }
                    }));
    var deployment = new DeploymentPlan(hosts, capabilityHosts);
    deployment.httpRoutes();
    deployment.resourceHosts();
    ModuleDictionaryPlan.declarations(deployment);
    return deployment;
  }

  private void visit(
      final String id, final List<String> path, final Map<String, ModuleDescriptor> ordered) {
    if (ordered.containsKey(id)) {
      return;
    }
    ModuleDescriptor module = modules.get(id);
    if (module == null) {
      throw failure("MISSING_MODULE", String.join(" -> ", path) + " -> " + id);
    }
    if (path.contains(id)) {
      throw failure("DEPENDENCY_CYCLE", String.join(" -> ", path) + " -> " + id);
    }
    path.add(id);
    module
        .requires()
        .forEach(
            (dependency, version) -> {
              ModuleDescriptor available = modules.get(dependency);
              if (available != null && available.version() != version) {
                throw failure(
                    "INCOMPATIBLE_MODULE_VERSION",
                    id
                        + " -> "
                        + dependency
                        + ": expected "
                        + version
                        + ", available "
                        + available.version());
              }
              visit(dependency, path, ordered);
            });
    path.removeLast();
    ordered.put(id, module);
  }

  private static void validatePackages(final List<ModuleDescriptor> selected) {
    var owners = new TreeMap<String, String>();
    for (ModuleDescriptor module : selected) {
      for (String name : module.packages()) {
        for (var existing : owners.entrySet()) {
          if (!existing.getValue().equals(module.id())
              && (name.equals(existing.getKey())
                  || name.startsWith(existing.getKey() + ".")
                  || existing.getKey().startsWith(name + "."))) {
            throw failure(
                "OVERLAPPING_PACKAGES",
                existing.getValue() + ":" + existing.getKey() + ", " + module.id() + ":" + name);
          }
        }
        owners.put(name, module.id());
      }
    }
  }

  private static ModuleCompositionException failure(final String code, final String detail) {
    return new ModuleCompositionException(code, detail);
  }
}
