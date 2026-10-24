package org.simplepoint.composition.fixture.startupone;

import org.simplepoint.platform.composition.ModuleInitialization;
import org.simplepoint.platform.composition.ModuleInitializationContext;
import org.simplepoint.platform.composition.ModuleInitializer;
import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.plugin.i18n.api.initialization.I18nModuleInitializer;
import org.simplepoint.plugin.rbac.resource.api.initialization.ResourceModuleInitializer;

/** Startup fixture without business RPC consumption declarations. */
@org.simplepoint.platform.composition.ModuleResourceOwner("historic-one")
@PlatformModule(
    id = "startup-one",
    packages = "org.simplepoint.composition.fixture.startupone",
    initializers = {
      ResourceModuleInitializer.class,
      I18nModuleInitializer.class,
      StartupOneModule.Probe.class
    })
public final class StartupOneModule {
  /** Captures its empty capability set to verify it cannot borrow another initializer's clients. */
  @ModuleInitialization
  public static final class Probe implements ModuleInitializer {
    private ModuleInitializationContext context;

    @Override
    public Runnable prepare(ModuleInitializationContext context) {
      this.context = context;
      return () -> {};
    }

    /** Returns the fixture's captured context. */
    public ModuleInitializationContext context() {
      return context;
    }
  }
}
