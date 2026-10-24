package org.simplepoint.data.jpa.composition;

import jakarta.persistence.Entity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.platform.composition.ModulePersistence;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.composition.ModuleStorageBindings;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypesScanner;

/** Discovers only selected modules' persistent types and validates their storage assignment. */
public record ModulePersistencePlan(
    Map<String, Store> stores, Map<String, String> entityOwners, ModuleStorageBindings bindings) {
  /** Freezes planning output before any persistence resources are created. */
  public ModulePersistencePlan {
    stores = Collections.unmodifiableMap(new TreeMap<>(stores));
    entityOwners = Collections.unmodifiableMap(new TreeMap<>(entityOwners));
  }

  /** Plans from existing module roots; default installation uses the ordinary dataSource bean. */
  public static ModulePersistencePlan discover(
      ModulePlan modules, Environment environment, ResourceLoader loader) {
    Map<String, String> assignments =
        Binder.get(environment)
            .bind("simplepoint.persistence.modules", Bindable.mapOf(String.class, String.class))
            .orElse(Map.of());
    Map<String, String> sources =
        Binder.get(environment)
            .bind("simplepoint.persistence.sources", Bindable.mapOf(String.class, String.class))
            .orElse(Map.of());
    var known = modules.modules().stream().map(module -> module.id()).collect(Collectors.toSet());
    if (!known.containsAll(assignments.keySet())) {
      throw new ModuleCompositionException(
          "UNKNOWN_PERSISTENCE_MODULE", assignments.keySet().toString());
    }
    var owners = new TreeMap<String, String>();
    var types = new TreeMap<String, Set<String>>();
    var packages = new TreeMap<String, Set<String>>();
    var moduleStores = assignStores(modules, assignments);
    var scanner = new PersistenceManagedTypesScanner(loader);
    for (var module : modules.modules()) {
      String store = moduleStores.get(module.id());
      var declaration = module.configuration().getDeclaredAnnotation(ModulePersistence.class);
      var roots = new ArrayList<>(module.packages());
      if (declaration != null) {
        types.computeIfAbsent(store, ignored -> new TreeSet<>());
        for (String root : declaration.entityPackages()) {
          if (!root.matches("[a-zA-Z_$][\\w$]*(\\.[a-zA-Z_$][\\w$]*)+")) {
            throw new ModuleCompositionException("INVALID_ENTITY_PACKAGE", module.id());
          }
          roots.add(root);
        }
      }
      var managed = scanner.scan(roots.toArray(String[]::new));
      for (String type : managed.getManagedClassNames()) {
        var previous = owners.putIfAbsent(type, module.id());
        if (previous != null && !previous.equals(module.id())) {
          throw new ModuleCompositionException("DUPLICATE_PERSISTENCE_OWNER", type);
        }
        types.computeIfAbsent(store, ignored -> new TreeSet<>()).add(type);
      }
      packages
          .computeIfAbsent(store, ignored -> new TreeSet<>())
          .addAll(managed.getManagedPackages());
    }
    if (!types.keySet().containsAll(sources.keySet())) {
      throw new ModuleCompositionException("UNUSED_MODULE_STORAGE", sources.keySet().toString());
    }
    var stores = new TreeMap<String, Store>();
    for (String store : types.keySet()) {
      String source = sources.getOrDefault(store, store.equals("default") ? "dataSource" : "");
      if (source.isBlank()) {
        throw new ModuleCompositionException("MISSING_MODULE_DATASOURCE", store);
      }
      String prefix = "simplepoint.persistence." + store + ".";
      final var binding =
          new ModuleStorageBindings.Binding(
              store,
              source,
              prefix + "entityManagerFactory",
              prefix + "entityManager",
              prefix + "transactionManager",
              prefix + "jdbcTemplate");
      final var storeModules =
          moduleStores.entrySet().stream()
              .filter(entry -> store.equals(entry.getValue()))
              .map(Map.Entry::getKey)
              .toList();
      var managed = new TreeSet<>(types.get(store));
      // Platform filters and inherited audit mappings are shared infrastructure, not owned tables.
      managed.add("org.simplepoint.core.base.entity.impl.BaseEntityImpl");
      managed.add("org.simplepoint.core.base.entity.impl.TenantBaseEntityImpl");
      var entities = new TreeMap<String, String>();
      for (String type : managed) {
        try {
          Class<?> candidate = Class.forName(type, false, loader.getClassLoader());
          Entity entity = candidate.getDeclaredAnnotation(Entity.class);
          if (entity != null) {
            String name = entity.name().isBlank() ? candidate.getSimpleName() : entity.name();
            if (entities.putIfAbsent(name, type) != null) {
              throw new ModuleCompositionException("DUPLICATE_JPA_ENTITY_NAME", store + ":" + name);
            }
          }
        } catch (ClassNotFoundException missing) {
          throw new ModuleCompositionException("MISSING_MANAGED_TYPE", type);
        }
      }
      stores.put(
          store,
          new Store(binding, storeModules, List.copyOf(managed), List.copyOf(packages.get(store))));
    }
    var bindings = new LinkedHashMap<String, ModuleStorageBindings.Binding>();
    stores
        .values()
        .forEach(store -> store.modules().forEach(module -> bindings.put(module, store.binding())));
    return new ModulePersistencePlan(stores, owners, new ModuleStorageBindings(bindings));
  }

  private static Map<String, String> assignStores(
      ModulePlan modules, Map<String, String> explicit) {
    var parents = new TreeMap<String, String>();
    modules.modules().forEach(module -> parents.put(module.id(), module.id()));
    for (var module : modules.modules()) {
      var declaration = module.configuration().getDeclaredAnnotation(ModulePersistence.class);
      if (declaration == null) {
        continue;
      }
      for (String dependency : declaration.sameStoreAs()) {
        if (!parents.containsKey(dependency) || !module.requires().containsKey(dependency)) {
          throw new ModuleCompositionException(
              "UNDECLARED_STORAGE_DEPENDENCY", module.id() + " -> " + dependency);
        }
        String left = root(parents, module.id());
        String right = root(parents, dependency);
        parents.put(
            left.compareTo(right) < 0 ? right : left, left.compareTo(right) < 0 ? left : right);
      }
    }
    var choices = new TreeMap<String, String>();
    explicit.forEach(
        (module, store) -> {
          token(store);
          String component = root(parents, module);
          String previous = choices.putIfAbsent(component, store);
          if (previous != null && !previous.equals(store)) {
            throw new ModuleCompositionException("INCOMPATIBLE_MODULE_STORES", module);
          }
        });
    var result = new TreeMap<String, String>();
    parents
        .keySet()
        .forEach(
            module -> result.put(module, choices.getOrDefault(root(parents, module), "default")));
    return result;
  }

  private static String root(Map<String, String> parents, String module) {
    String current = module;
    while (!current.equals(parents.get(current))) {
      current = parents.get(current);
    }
    return current;
  }

  private static void token(String value) {
    if (!value.matches("[a-z][a-z0-9-]{0,63}")) {
      throw new ModuleCompositionException("INVALID_MODULE_STORAGE", "");
    }
  }

  /** A group uses one persistence unit and transaction manager, independent of its host name. */
  public record Store(
      ModuleStorageBindings.Binding binding,
      List<String> modules,
      List<String> managedTypes,
      List<String> managedPackages) {
    /** Defensive copies protect the validated group. */
    public Store {
      modules = List.copyOf(modules);
      managedTypes = List.copyOf(managedTypes);
      managedPackages = List.copyOf(managedPackages);
    }
  }
}
