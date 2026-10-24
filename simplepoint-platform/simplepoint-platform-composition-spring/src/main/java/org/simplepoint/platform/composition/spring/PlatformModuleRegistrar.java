package org.simplepoint.platform.composition.spring;

import java.util.List;
import java.util.Objects;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.platform.composition.ModuleDefaults;
import org.simplepoint.platform.composition.ModulePlan;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;

/** Activates only packages belonging to the validated module selection. */
public final class PlatformModuleRegistrar
    implements ImportBeanDefinitionRegistrar, EnvironmentAware, ResourceLoaderAware {

  private Environment environment;
  private ResourceLoader resourceLoader;

  @Override
  public void setEnvironment(final Environment environment) {
    this.environment = environment;
  }

  @Override
  public void setResourceLoader(final ResourceLoader resourceLoader) {
    this.resourceLoader = resourceLoader;
  }

  @Override
  public void registerBeanDefinitions(
      final AnnotationMetadata metadata, final BeanDefinitionRegistry registry) {
    if (registry.containsBeanDefinition(ModulePlan.class.getName())) {
      throw new ModuleCompositionException("DUPLICATE_COMPOSITION_ENTRY", metadata.getClassName());
    }
    var attributes =
        Objects.requireNonNull(
            metadata.getAnnotationAttributes(EnablePlatformModules.class.getName()));
    List<String> roots =
        Binder.get(environment)
            .bind("simplepoint.modules.active", Bindable.listOf(String.class))
            .orElse(List.of((String[]) attributes.get("value")));
    if (roots.isEmpty()) {
      throw new ModuleCompositionException("NO_ACTIVE_MODULES", metadata.getClassName());
    }
    ModulePlan plan = ModuleCatalog.discover(resourceLoader.getClassLoader()).plan(roots);
    var defaults = ModuleDefaults.load(plan, resourceLoader.getClassLoader());
    if (!(environment instanceof ConfigurableEnvironment configurable)) {
      throw new ModuleCompositionException("IMMUTABLE_MODULE_ENVIRONMENT", metadata.getClassName());
    }
    if (!defaults.isEmpty()) {
      var values = new java.util.LinkedHashMap<String, Object>(defaults);
      configurable
          .getPropertySources()
          .addLast(new MapPropertySource(ModuleDefaults.class.getName(), values));
    }
    String isolation = "simplepoint.service-router.consumer.local-isolation";
    if (!environment.getProperty(isolation, Boolean.class, true)) {
      throw new ModuleCompositionException(
          "MODULE_CALL_ISOLATION_REQUIRED", metadata.getClassName());
    }
    configurable
        .getPropertySources()
        .addLast(
            new MapPropertySource(
                "simplepoint.module-call-boundary", java.util.Map.of(isolation, true)));
    registry.registerBeanDefinition(
        ModulePlan.class.getName(),
        BeanDefinitionBuilder.genericBeanDefinition(ModulePlan.class, () -> plan)
            .getBeanDefinition());

    var scanner = new ClassPathBeanDefinitionScanner(registry, true, environment, resourceLoader);
    // Boot must evaluate auto-configurations after all selected module definitions are known.
    // Scanning them as domain components evaluates missing-bean conditions too early.
    scanner.addExcludeFilter(new org.springframework.core.type.filter.AnnotationTypeFilter(
        org.springframework.boot.autoconfigure.AutoConfiguration.class));
    scanner.scan(
        plan.modules().stream()
            .flatMap(module -> module.packages().stream())
            .toArray(String[]::new));
    plan.modules()
        .forEach(
            module -> {
              module
                  .provides()
                  .values()
                  .forEach(type -> ModuleClientFactoryBean.register(registry, type));
              module
                  .consumes()
                  .values()
                  .forEach(type -> ModuleClientFactoryBean.register(registry, type));
            });
    registry.registerBeanDefinition(
        ModuleCapabilityValidator.class.getName(),
        BeanDefinitionBuilder.genericBeanDefinition(ModuleCapabilityValidator.class)
            .setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR)
            .getBeanDefinition());
    plan.modules().stream()
        .flatMap(module -> module.initializers().stream())
        .distinct()
        .forEach(
            type -> {
              String name = ModuleInitializationRunner.beanName(type);
              if (registry.containsBeanDefinition(name)) {
                throw new ModuleCompositionException("CONFLICTING_MODULE_INITIALIZER", name);
              }
              registry.registerBeanDefinition(
                  name,
                  BeanDefinitionBuilder.genericBeanDefinition(type)
                      .setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR)
                      .getBeanDefinition());
            });
    registry.registerBeanDefinition(
        ModuleInitializationRunner.class.getName(),
        BeanDefinitionBuilder.genericBeanDefinition(ModuleInitializationRunner.class)
            .setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR)
            .getBeanDefinition());
  }
}
