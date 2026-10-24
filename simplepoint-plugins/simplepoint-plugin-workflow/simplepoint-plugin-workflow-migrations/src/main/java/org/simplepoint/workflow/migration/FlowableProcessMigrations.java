package org.simplepoint.workflow.migration;

import java.util.List;
import org.simplepoint.data.migration.ModuleMigrationContributor;
import org.simplepoint.data.migration.ModuleMigrationPlan;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.composition.ModuleStorageBindings;

/** Describes native engine SQL without loading an engine, a Spring context or business code. */
public final class FlowableProcessMigrations implements ModuleMigrationContributor {
  public static final String OWNER = "flowable-process";
  public static final String ENGINE_VERSION = "8.0.0.0";

  @Override
  public List<ModuleMigrationPlan.ModuleSchema> contribute(
      ModulePlan modules, ModuleStorageBindings bindings) {
    return modules.modules().stream()
        .filter(module -> module.configuration().isAnnotationPresent(FlowableProcessSchema.class))
        .map(module -> bindings.require(module.id()).store())
        .distinct()
        .sorted()
        .map(store -> ModuleMigrationPlan.loadSchema(OWNER, store, getClass().getClassLoader()))
        .toList();
  }
}
