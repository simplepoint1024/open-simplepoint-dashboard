package org.simplepoint.platform.composition.spring;

import com.simplepoint.service.router.registry.LocalServiceRegistry;
import java.util.TreeMap;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.platform.composition.ModulePlan;
import org.springframework.beans.factory.SmartInitializingSingleton;

/** Fails startup when the assembled providers differ from the module declarations. */
final class ModuleCapabilityValidator implements SmartInitializingSingleton {

  private final ModulePlan plan;
  private final LocalServiceRegistry registry;

  ModuleCapabilityValidator(final ModulePlan plan, final LocalServiceRegistry registry) {
    this.plan = plan;
    this.registry = registry;
  }

  @Override
  public void afterSingletonsInstantiated() {
    var expected = new TreeMap<String, Class<?>>();
    plan.modules().forEach(module -> expected.putAll(module.provides()));
    for (var provider : registry.all()) {
      String key = provider.metadata().name() + ":" + provider.metadata().version();
      Class<?> type = expected.remove(key);
      if (type == null) {
        throw new ModuleCompositionException("UNDECLARED_CAPABILITY", key);
      }
      if (type != provider.interfaceType()) {
        throw new ModuleCompositionException("INCOMPATIBLE_CAPABILITY_TYPE", key);
      }
    }
    if (!expected.isEmpty()) {
      throw new ModuleCompositionException("MISSING_LOCAL_PROVIDER", expected.keySet().toString());
    }
  }
}
