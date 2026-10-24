package org.simplepoint.platform.composition;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import org.simplepoint.platform.dictionary.DictionaryContribution;
import org.simplepoint.platform.dictionary.ModuleDictionaries;

/**
 * Validates selected dictionary metadata without runtime services, database access or host names.
 */
public final class ModuleDictionaryPlan {
  private ModuleDictionaryPlan() {}

  /** Returns one immutable declaration per selected owning module, rejecting cross-owner codes. */
  public static Map<String, DictionaryContribution> declarations(ModulePlan modules) {
    var declarations = new TreeMap<String, DictionaryContribution>();
    var owners = new TreeMap<String, String>();
    for (var module : modules.modules()) {
      var annotation = module.configuration().getDeclaredAnnotation(ModuleDictionaries.class);
      if (annotation == null) {
        continue;
      }
      if (annotation.value().getClassLoader() != module.configuration().getClassLoader()) {
        throw new ModuleCompositionException("DICTIONARY_DEFINITION_LOADER_MISMATCH", module.id());
      }
      DictionaryContribution contribution;
      try {
        contribution = annotation.value().getConstructor().newInstance().declaration();
      } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
        throw new ModuleCompositionException("INVALID_MODULE_DICTIONARIES", module.id());
      }
      if (contribution == null || !contribution.moduleCode().equals(module.id())) {
        throw new ModuleCompositionException("DICTIONARY_MODULE_MISMATCH", module.id());
      }
      if (declarations.putIfAbsent(module.id(), contribution) != null) {
        throw new ModuleCompositionException("DUPLICATE_MODULE_HOST", module.id());
      }
      for (var dictionary : contribution.dictionaries()) {
        var previous = owners.putIfAbsent(dictionary.code(), module.id());
        if (previous != null) {
          throw new ModuleCompositionException("DUPLICATE_DICTIONARY_OWNER", dictionary.code());
        }
      }
    }
    return Collections.unmodifiableMap(declarations);
  }

  /** Validates all hosts together so splitting hosts cannot hide duplicate dictionary codes. */
  public static Map<String, DictionaryContribution> declarations(DeploymentPlan deployment) {
    return declarations(
        new ModulePlan(
            deployment.hosts().values().stream().flatMap(plan -> plan.modules().stream()).toList(),
            Map.of(),
            Map.of()));
  }
}
