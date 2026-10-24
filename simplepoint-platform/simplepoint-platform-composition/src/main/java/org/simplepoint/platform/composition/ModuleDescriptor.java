package org.simplepoint.platform.composition;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.simplepoint.remoting.RemoteContractResolver;

/**
 * Immutable module metadata read without initializing its configuration class.
 *
 * @param id logical module
 * @param version exact module revision
 * @param configuration configuration entry point
 * @param packages exclusive component roots
 * @param requires local module dependencies and revisions
 * @param provides capability/version keys mapped to their interface types
 * @param consumes consumed capabilities, including declared startup dependencies
 * @param initializers ordered reusable startup contributions
 */
public record ModuleDescriptor(
    String id,
    int version,
    Class<?> configuration,
    List<String> packages,
    Map<String, Integer> requires,
    Map<String, Class<?>> provides,
    Map<String, Class<?>> consumes,
    List<Class<? extends ModuleInitializer>> initializers) {

  /** Copies all metadata collections; instances are normally created by {@link #from}. */
  public ModuleDescriptor {
    packages = List.copyOf(packages);
    initializers = List.copyOf(initializers);
    requires = immutable(requires);
    provides = immutable(provides);
    consumes = immutable(consumes);
  }

  /**
   * Reads and validates a module declaration.
   *
   * @param configuration annotated configuration class
   * @return validated immutable metadata
   */
  public static ModuleDescriptor from(final Class<?> configuration) {
    PlatformModule module = configuration.getDeclaredAnnotation(PlatformModule.class);
    if (module == null
        || configuration.isInterface()
        || configuration.isAnnotation()
        || configuration.isEnum()) {
      throw failure("INVALID_MODULE", configuration.getName());
    }
    validateIdentity(module.id(), module.version());
    if (module.packages().length == 0) {
      throw failure("MISSING_PACKAGES", module.id());
    }
    var packages = new TreeSet<String>();
    for (String name : module.packages()) {
      if (!name.matches("[a-zA-Z_$][\\w$]*(\\.[a-zA-Z_$][\\w$]*)+") || !packages.add(name)) {
        throw failure("INVALID_PACKAGE", module.id() + ": " + name);
      }
    }
    var requires = new TreeMap<String, Integer>();
    for (ModuleDependency dependency : module.requires()) {
      validateIdentity(dependency.id(), dependency.version());
      if (requires.putIfAbsent(dependency.id(), dependency.version()) != null) {
        throw failure("DUPLICATE_DEPENDENCY", module.id() + " -> " + dependency.id());
      }
    }
    var initializers = new java.util.LinkedHashSet<Class<? extends ModuleInitializer>>();
    var consumes = contracts(module.consumes());
    for (var initializer : module.initializers()) {
      var declaration = initializer.getDeclaredAnnotation(ModuleInitialization.class);
      if (declaration == null
          || initializer.isInterface()
          || java.lang.reflect.Modifier.isAbstract(initializer.getModifiers())
          || !initializers.add(initializer)) {
        throw failure("INVALID_MODULE_INITIALIZER", initializer.getName());
      }
      contracts(declaration.consumes())
          .forEach(
              (capability, type) -> {
                var previous = consumes.putIfAbsent(capability, type);
                if (previous != null && previous != type) {
                  throw failure("INCOMPATIBLE_CAPABILITY_TYPE", capability);
                }
              });
    }
    return new ModuleDescriptor(
        module.id(),
        module.version(),
        configuration,
        List.copyOf(packages),
        requires,
        contracts(module.provides()),
        consumes,
        List.copyOf(initializers));
  }

  /** Returns the historical resource identity only when this module owns a complete declaration. */
  public java.util.Optional<String> resourceOwner() {
    var declaration = configuration.getDeclaredAnnotation(ModuleResourceOwner.class);
    return declaration == null
        ? java.util.Optional.empty()
        : java.util.Optional.of(declaration.value());
  }

  private static Map<String, Class<?>> contracts(final Class<?>[] types) {
    var result = new TreeMap<String, Class<?>>();
    Arrays.stream(types)
        .forEach(
            type -> {
              var contract =
                  RemoteContractResolver.resolve(type)
                      .orElseThrow(() -> failure("INVALID_CAPABILITY", type.getName()));
              if (contract.name().contains(":")
                  || contract.version().contains(":")
                  || !contract.name().equals(contract.name().strip())
                  || !contract.version().equals(contract.version().strip())) {
                throw failure("INVALID_CAPABILITY", type.getName());
              }
              String key = contract.name() + ":" + contract.version();
              if (result.putIfAbsent(key, type) != null) {
                throw failure("DUPLICATE_CAPABILITY", key);
              }
            });
    return result;
  }

  private static void validateIdentity(final String id, final int version) {
    if (!id.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*") || version < 1) {
      throw failure("INVALID_MODULE_IDENTITY", id + ":" + version);
    }
  }

  private static <T> Map<String, T> immutable(final Map<String, T> source) {
    return Collections.unmodifiableMap(new TreeMap<>(source));
  }

  private static ModuleCompositionException failure(final String code, final String detail) {
    return new ModuleCompositionException(code, detail);
  }
}
