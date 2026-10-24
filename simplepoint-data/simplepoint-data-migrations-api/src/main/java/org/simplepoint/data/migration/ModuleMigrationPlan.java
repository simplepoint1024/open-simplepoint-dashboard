package org.simplepoint.data.migration;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.composition.ModuleResources;
import org.simplepoint.platform.composition.ModuleSharedSchemas;
import org.simplepoint.platform.composition.ModuleStorageBindings;

/** An immutable migration catalog; discovery requires neither a database nor an execution engine. */
public record ModuleMigrationPlan(List<ModuleSchema> schemas, List<String> undeclaredModules) {
  /** Freezes dependency-first migration order. */
  public ModuleMigrationPlan {
    schemas = List.copyOf(schemas);
    undeclaredModules = List.copyOf(undeclaredModules);
    var identities = new LinkedHashSet<String>();
    var owners = new LinkedHashMap<String, String>();
    for (var schema : schemas) {
      if (!identities.add(schema.store() + ":" + schema.module())) {
        throw new ModuleCompositionException("DUPLICATE_MODULE_MIGRATION", schema.module());
      }
      for (String table : schema.tables()) {
        if (owners.putIfAbsent(schema.store() + ":" + table, schema.module()) != null) {
          throw new ModuleCompositionException("DUPLICATE_MIGRATION_TABLE_OWNER", table);
        }
      }
    }
  }

  /** Creates an explicit plan for already resolved schema contributions. */
  public ModuleMigrationPlan(List<ModuleSchema> schemas) {
    this(schemas, List.of());
  }

  /** Loads only explicitly declared, uniquely addressed module migration assets. */
  public static ModuleMigrationPlan discover(ModulePlan modules, ModuleStorageBindings bindings) {
    var schemas = new ArrayList<ModuleSchema>();
    var undeclared = new ArrayList<String>();
    for (var module : modules.modules()) {
      var declaration = module.configuration().getDeclaredAnnotation(ModuleMigrations.class);
      if (declaration == null) {
        if (bindings.modules().containsKey(module.id())) {
          undeclared.add(module.id());
        }
        continue;
      }
      if (!declaration.ownsSchema()) {
        continue;
      }
      schemas.add(
          loadSchema(
              module.id(),
              bindings.require(module.id()).store(),
              module.configuration().getClassLoader()));
    }
    return withContributors(modules, bindings, new ModuleMigrationPlan(schemas, undeclared));
  }

  private static ModuleMigrationPlan withContributors(
      ModulePlan modules, ModuleStorageBindings bindings, ModuleMigrationPlan domain) {
    try {
      var contributors =
          ServiceLoader.load(
                  ModuleMigrationContributor.class, ModuleMigrationPlan.class.getClassLoader())
              .stream()
              .sorted(java.util.Comparator.comparing(provider -> provider.type().getName()))
              .map(ServiceLoader.Provider::get)
              .toList();
      return contribute(modules, bindings, domain, contributors);
    } catch (ServiceConfigurationError invalid) {
      throw new ModuleCompositionException("INVALID_MIGRATION_CONTRIBUTOR", "service declaration");
    }
  }

  // Kept package-private so contributor invariants can be tested without global service fixtures.
  static ModuleMigrationPlan contribute(
      ModulePlan modules,
      ModuleStorageBindings bindings,
      ModuleMigrationPlan domain,
      List<ModuleMigrationContributor> contributors) {
    var selected =
        modules.modules().stream()
            .map(module -> module.id())
            .collect(java.util.stream.Collectors.toSet());
    var stores =
        selected.stream()
            .filter(bindings.modules()::containsKey)
            .map(module -> bindings.require(module).store())
            .collect(java.util.stream.Collectors.toSet());
    var schemas = new ArrayList<>(domain.schemas());
    for (var contributor : contributors) {
      for (var schema : List.copyOf(contributor.contribute(modules, bindings))) {
        if (selected.contains(schema.module()) || !stores.contains(schema.store())) {
          throw new ModuleCompositionException("INVALID_SHARED_MIGRATION_OWNER", schema.module());
        }
        schemas.add(schema);
      }
    }
    var result = new ModuleMigrationPlan(schemas, domain.undeclaredModules());
    for (var module : modules.modules()) {
      for (var annotation : module.configuration().getDeclaredAnnotations()) {
        var required = annotation.annotationType().getDeclaredAnnotation(ModuleSharedSchemas.class);
        if (required == null) {
          continue;
        }
        for (String owner : required.value()) {
          if (!owner.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*") || selected.contains(owner)) {
            throw new ModuleCompositionException(
                "INVALID_SHARED_MIGRATION_REQUIREMENT", module.id());
          }
          String store = bindings.require(module.id()).store();
          if (result.schemas().stream()
              .noneMatch(schema -> schema.module().equals(owner) && schema.store().equals(store))) {
            throw new ModuleCompositionException(
                "MISSING_SHARED_MIGRATION_CONTRIBUTOR", module.id() + ":" + owner);
          }
        }
      }
    }
    return result;
  }

  /** Reads a uniquely addressed module or infrastructure manifest without opening its database. */
  public static ModuleSchema loadSchema(String owner, String store, ClassLoader loader) {
    var manifest = new Properties();
    try {
      manifest.load(
          new StringReader(
              new String(
                  ModuleResources.read(owner, loader, "db/manifest.properties"),
                  StandardCharsets.UTF_8)));
    } catch (IOException failure) {
      throw new ModuleCompositionException("INVALID_MIGRATION_MANIFEST", owner);
    }
    if (!manifest.stringPropertyNames().equals(Set.of("tables", "dialects", "migrations"))) {
      throw new ModuleCompositionException("INVALID_MIGRATION_MANIFEST", owner);
    }
    var tables = entries(manifest, "tables", "[a-z][a-z0-9_]{0,62}");
    var dialects = entries(manifest, "dialects", "postgresql|h2");
    var names = entries(manifest, "migrations", "V[1-9][0-9]*(?:_[0-9]+)*__[a-z][a-z0-9_]*\\.sql");
    if (names.size() > 256 || tables.size() > 1024) {
      throw new ModuleCompositionException("MIGRATION_MANIFEST_LIMIT", owner);
    }
    var scripts = new TreeMap<String, List<Script>>();
    long bytes = 0;
    for (String dialect : dialects) {
      var files = new ArrayList<Script>();
      for (String name : names) {
        byte[] source = ModuleResources.read(owner, loader, "db/" + dialect + "/" + name);
        bytes += source.length;
        if (bytes > 16L * 1024 * 1024) {
          throw new ModuleCompositionException("MIGRATION_ASSET_LIMIT", owner);
        }
        files.add(new Script(name, new String(source, StandardCharsets.UTF_8)));
      }
      scripts.put(dialect, List.copyOf(files));
    }
    return new ModuleSchema(owner, store, historyTable(owner), tables, scripts);
  }

  private static List<String> entries(Properties properties, String key, String pattern) {
    var result = new LinkedHashSet<String>();
    for (String entry : properties.getProperty(key).split(",", -1)) {
      String value = entry.trim();
      if (!value.matches(pattern) || !result.add(value)) {
        throw new ModuleCompositionException("INVALID_MIGRATION_MANIFEST", key);
      }
    }
    return List.copyOf(result);
  }

  private static String historyTable(String module) {
    try {
      // Stable logical identity, independent of host/store names, within every supported DB limit.
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(module.getBytes(StandardCharsets.UTF_8));
      return "sp_migration_" + HexFormat.of().formatHex(hash).substring(0, 32);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  /** A domain or infrastructure owner's objects and scripts; no credentials or host names. */
  public record ModuleSchema(
      String module,
      String store,
      String historyTable,
      List<String> tables,
      Map<String, List<Script>> scripts) {
    /** Defensively copies declaration collections. */
    public ModuleSchema {
      if (module == null
          || !module.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")
          || store == null
          || !store.matches("[a-z][a-z0-9-]{0,63}")
          || !ModuleMigrationPlan.historyTable(module).equals(historyTable)) {
        throw new ModuleCompositionException("INVALID_MODULE_MIGRATION", "identity");
      }
      tables = List.copyOf(tables);
      if (tables.isEmpty()
          || tables.size() != Set.copyOf(tables).size()
          || tables.stream()
              .anyMatch(
                  table ->
                      !table.matches("[a-z][a-z0-9_]{0,62}")
                          || table.startsWith("sp_migration_"))) {
        throw new ModuleCompositionException("INVALID_MODULE_MIGRATION", "tables");
      }
      if (scripts.isEmpty()
          || !Set.of("h2", "postgresql").containsAll(scripts.keySet())
          || scripts.values().stream().anyMatch(List::isEmpty)) {
        throw new ModuleCompositionException("INVALID_MODULE_MIGRATION", "dialects");
      }
      var copy = new TreeMap<String, List<Script>>();
      scripts.forEach(
          (dialect, files) -> {
            if (files.stream().map(Script::name).distinct().count() != files.size()) {
              throw new ModuleCompositionException("INVALID_MODULE_MIGRATION", "duplicate script");
            }
            copy.put(dialect, List.copyOf(files));
          });
      scripts = Collections.unmodifiableMap(copy);
    }
  }

  /** SQL is immutable and comes only from its selected module artifact. */
  public record Script(String name, String sql) {
    /** Content identity for reviewed installation plans; Flyway retains its own history checksum. */
    public String sha256() {
      try {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8)));
      } catch (NoSuchAlgorithmException impossible) {
        throw new IllegalStateException("SHA-256 unavailable", impossible);
      }
    }

    /** Baseline zero must never hide a declared migration; SQL resources start at version one. */
    public Script {
      if (name == null
          || !name.matches("V[1-9][0-9]*(?:_[0-9]+)*__[a-z][a-z0-9_]*\\.sql")
          || sql == null
          || sql.isBlank()) {
        throw new ModuleCompositionException("INVALID_MODULE_MIGRATION", "script");
      }
    }
  }
}
