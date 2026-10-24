package org.simplepoint.platform.bootstrap;

import org.simplepoint.platform.bootstrap.properties.PlatformBootstrapProperties;
import org.simplepoint.platform.bootstrap.service.PlatformContributionService;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.platform.composition.ModuleInitialization;
import org.simplepoint.platform.composition.ModuleInitializationContext;
import org.simplepoint.platform.composition.ModuleInitializer;

/**
 * Runs module-owned bootstrap contributions using a stable registry scope and module credentials.
 */
@ModuleInitialization(
    consumes = PlatformContributionService.class,
    stage = org.simplepoint.platform.composition.ModuleInitializationStage.BOOTSTRAP)
public final class PlatformBootstrapModuleInitializer implements ModuleInitializer {
  private final ModuleBootstrapContributions contributions;
  private final PlatformBootstrapProperties properties;

  /** Receives local ownership and policy; the framework supplies the module's RPC client. */
  public PlatformBootstrapModuleInitializer(
      ModuleBootstrapContributions contributions, PlatformBootstrapProperties properties) {
    this.contributions = contributions;
    this.properties = properties;
  }

  @Override
  public Runnable prepare(ModuleInitializationContext context) {
    var tasks = prepareTasks(context);
    return () -> tasks.forEach(task -> task.action().run());
  }

  @Override
  public java.util.List<org.simplepoint.platform.composition.ModuleInitializationTask> prepareTasks(
      ModuleInitializationContext context) {
    var selected = BootstrapExecution.prepare(contributions.forModule(context.moduleId()));
    if (!properties.isEnabled() || !properties.isModuleEnabled(context.moduleId())) {
      return java.util.List.of();
    }
    if (!properties.isFailFast()) {
      throw new ModuleCompositionException(
          "MODULE_BOOTSTRAP_FAIL_FAST_REQUIRED", context.moduleId());
    }
    selected = selected.stream().filter(properties::isContributionEnabled).toList();
    String scope = ModuleBootstrapScope.of(context.moduleId());
    var registry = context.client(PlatformContributionService.class);
    return selected.stream()
        .map(
            contribution ->
                new org.simplepoint.platform.composition.ModuleInitializationTask(
                    contribution.order(),
                    () -> {
                      try {
                        BootstrapExecution.apply(scope, contribution, registry);
                      } catch (Exception failure) {
                        throw new IllegalStateException(
                            "MODULE_BOOTSTRAP_FAILED: "
                                + context.moduleId()
                                + ":"
                                + contribution.contributionKey(),
                            failure);
                      }
                    }))
        .toList();
  }
}
