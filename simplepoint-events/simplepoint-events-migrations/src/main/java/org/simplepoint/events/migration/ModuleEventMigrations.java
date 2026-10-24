package org.simplepoint.events.migration;

import java.util.List;
import org.simplepoint.data.migration.ModuleMigrationContributor;
import org.simplepoint.data.migration.ModuleMigrationPlan;
import org.simplepoint.events.api.ModuleEvents;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.composition.ModuleStorageBindings;

/** Derives one durable event ledger per store used by the selected event-owning modules. */
public final class ModuleEventMigrations implements ModuleMigrationContributor {
  /** Shared schema identity stays stable when modules move between hosts or stores. */
  public static final String OWNER = "platform-events";

  @Override
  public List<ModuleMigrationPlan.ModuleSchema> contribute(
      ModulePlan modules, ModuleStorageBindings bindings) {
    return modules.modules().stream()
        .filter(module -> module.configuration().isAnnotationPresent(ModuleEvents.class))
        .map(module -> bindings.require(module.id()).store())
        .distinct()
        .sorted()
        .map(store -> ModuleMigrationPlan.loadSchema(OWNER, store, getClass().getClassLoader()))
        .toList();
  }
}
