package org.simplepoint.platform.dictionary.registration;

import java.util.List;
import java.util.Map;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.platform.composition.ModuleDictionaryPlan;
import org.simplepoint.platform.composition.ModuleInitialization;
import org.simplepoint.platform.composition.ModuleInitializationContext;
import org.simplepoint.platform.composition.ModuleInitializationStage;
import org.simplepoint.platform.composition.ModuleInitializationTask;
import org.simplepoint.platform.composition.ModuleInitializer;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.dictionary.DictionaryContribution;
import org.simplepoint.platform.dictionary.DictionaryFingerprint;

/**
 * Uses only the module-bound client; completion is committed in the dictionary owner's database.
 */
@ModuleInitialization(
    consumes = DictionaryRegistrationService.class,
    stage = ModuleInitializationStage.BOOTSTRAP)
public final class DictionaryModuleInitializer implements ModuleInitializer {
  private final Map<String, DictionaryContribution> declarations;

  /** Reads selected metadata without opening connections or granting installation authority. */
  public DictionaryModuleInitializer(ModulePlan plan) {
    declarations = ModuleDictionaryPlan.declarations(plan);
  }

  @Override
  public List<ModuleInitializationTask> prepareTasks(ModuleInitializationContext context) {
    var declaration = requireDeclaration(context);
    return List.of(new ModuleInitializationTask(declaration.order(), prepare(context)));
  }

  @Override
  public Runnable prepare(ModuleInitializationContext context) {
    var declaration = requireDeclaration(context);
    var client = context.client(DictionaryRegistrationService.class);
    var fingerprint = DictionaryFingerprint.of(declaration);
    return () -> {
      var receipt = client.apply(declaration);
      if (receipt == null
          || !receipt.moduleCode().equals(declaration.moduleCode())
          || !receipt.contributionKey().equals(declaration.contributionKey())
          || !receipt.version().equals(declaration.version())
          || !receipt.fingerprint().equals(fingerprint)) {
        throw new ModuleCompositionException("DICTIONARY_RECEIPT_MISMATCH", context.moduleId());
      }
    };
  }

  private DictionaryContribution requireDeclaration(ModuleInitializationContext context) {
    var declaration = declarations.get(context.moduleId());
    if (declaration == null) {
      throw new ModuleCompositionException("MODULE_DICTIONARIES_REQUIRED", context.moduleId());
    }
    return declaration;
  }
}
