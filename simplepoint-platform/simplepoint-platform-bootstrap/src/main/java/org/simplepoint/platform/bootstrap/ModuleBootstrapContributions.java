package org.simplepoint.platform.bootstrap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.platform.composition.ModulePlan;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/**
 * Resolves bootstrap ownership from real factory/component types, not mutable contribution labels.
 */
public final class ModuleBootstrapContributions implements SmartInitializingSingleton {
  private final ModulePlan plan;
  private final ConfigurableListableBeanFactory factory;
  private Map<String, List<String>> owners;

  /** Captures the selected assembly and its local bean definitions. */
  public ModuleBootstrapContributions(ModulePlan plan, ConfigurableListableBeanFactory factory) {
    this.plan = plan;
    this.factory = factory;
  }

  @Override
  public void afterSingletonsInstantiated() {
    var bindings = new LinkedHashMap<String, List<String>>();
    for (String name :
        factory.getBeanNamesForType(PlatformBootstrapContribution.class, true, true)) {
      String factoryName =
          factory.containsBeanDefinition(name)
              ? factory.getMergedBeanDefinition(name).getFactoryBeanName()
              : null;
      Class<?> source =
          AopUtils.getTargetClass(factory.getBean(factoryName == null ? name : factoryName));
      var owner =
          plan.ownerOf(source)
              .filter(module -> module.configuration().getClassLoader() == source.getClassLoader())
              .orElseThrow(
                  () -> new ModuleCompositionException("UNOWNED_BOOTSTRAP_CONTRIBUTION", name));
      if (!owner.initializers().contains(PlatformBootstrapModuleInitializer.class)) {
        throw new ModuleCompositionException("UNDECLARED_MODULE_BOOTSTRAP", owner.id());
      }
      bindings.computeIfAbsent(owner.id(), ignored -> new ArrayList<>()).add(name);
    }
    var immutable = new LinkedHashMap<String, List<String>>();
    bindings.forEach((owner, names) -> immutable.put(owner, List.copyOf(names)));
    owners = Map.copyOf(immutable);
  }

  /** Materializes only the owning module's contributions once during startup preparation. */
  public List<BootstrapContribution> forModule(String module) {
    if (owners == null) {
      throw new IllegalStateException("BOOTSTRAP_OWNERSHIP_NOT_READY");
    }
    return owners.getOrDefault(module, List.of()).stream()
        .map(name -> factory.getBean(name, PlatformBootstrapContribution.class).contribution())
        .toList();
  }
}
