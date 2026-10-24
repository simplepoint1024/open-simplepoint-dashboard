package org.simplepoint.data.migration;

import java.util.List;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.composition.ModuleStorageBindings;

/**
 * Supplies shared infrastructure schemas derived from selected modules and their storage bindings.
 *
 * <p>Register through {@link java.util.ServiceLoader}. Implementations are pure metadata: no
 * database access, credentials or runtime side effects. Return no contributions when the selected
 * modules do not use the infrastructure. Shared owners must differ from selected domain module
 * identities.
 */
public interface ModuleMigrationContributor {
  /** Returns each required infrastructure schema once per selected store, in installation order. */
  List<ModuleMigrationPlan.ModuleSchema> contribute(
      ModulePlan modules, ModuleStorageBindings bindings);
}
