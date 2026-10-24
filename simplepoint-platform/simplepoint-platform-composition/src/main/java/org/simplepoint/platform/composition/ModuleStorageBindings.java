package org.simplepoint.platform.composition;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Framework-neutral storage bean names shared by persistence and durable event adapters. */
public record ModuleStorageBindings(Map<String, Binding> modules) {
  /** Keeps the validated binding immutable and deterministic. */
  public ModuleStorageBindings {
    modules.forEach(
        (module, binding) -> {
          if (module == null || module.isBlank()) {
            throw new ModuleCompositionException("INVALID_MODULE_STORAGE_BINDING", "module");
          }
          Objects.requireNonNull(binding, "binding");
        });
    modules = Collections.unmodifiableMap(new TreeMap<>(modules));
  }

  /** Resolves the owning module; no fallback to another module or a host primary bean. */
  public Binding require(String module) {
    var binding = modules.get(module);
    if (binding == null) {
      throw new ModuleCompositionException("MISSING_MODULE_STORAGE", module);
    }
    return binding;
  }

  /** Names identify container-managed resources, never connection URLs or secrets. */
  public record Binding(
      String store,
      String dataSource,
      String entityManagerFactory,
      String entityManager,
      String transactionManager,
      String jdbcTemplate) {
    /** Rejects incomplete infrastructure names before any adapters consume the binding. */
    public Binding {
      for (String name :
          new String[] {
            store, dataSource, entityManagerFactory, entityManager, transactionManager, jdbcTemplate
          }) {
        if (name == null || name.isBlank() || !name.equals(name.trim())) {
          throw new ModuleCompositionException("INVALID_MODULE_STORAGE_BINDING", "resource name");
        }
      }
    }
  }
}
