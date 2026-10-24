package org.simplepoint.platform.http.servlet;

import java.util.LinkedHashMap;
import java.util.function.Predicate;
import org.simplepoint.platform.composition.ModuleHttpPlan;
import org.simplepoint.platform.composition.ModulePlan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;
import org.springframework.util.ClassUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Adds logical prefixes in modular servlet hosts; legacy gateway-stripped paths remain unchanged.
 */
@AutoConfiguration
@ConditionalOnBean(ModulePlan.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ModuleHttpAutoConfiguration {
  @Bean
  ModuleHttpPlan moduleHttpPlan(ModulePlan modules) {
    return ModuleHttpPlan.from(modules);
  }

  /** Applies to all MVC handler mappings, including the platform dynamic-plugin mapping. */
  @Bean
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  static BeanPostProcessor moduleHttpHandlerMappings(ObjectProvider<ModuleHttpPlan> plans) {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessBeforeInitialization(Object bean, String name) {
        if (bean instanceof RequestMappingHandlerMapping mapping) {
          var plan = plans.getObject();
          var prefixes = new LinkedHashMap<String, Predicate<Class<?>>>(mapping.getPathPrefixes());
          new java.util.TreeSet<>(plan.namespaces().values())
              .forEach(
                  namespace -> {
                    if (namespace.equals("/")) {
                      // These adapters already declare their complete protocol paths.
                      // Actual mappings still pass the same bounded ownership validation below.
                      return;
                    }
                    if (prefixes.containsKey(namespace)) {
                      throw new org.simplepoint.platform.composition.ModuleCompositionException(
                          "CONFLICTING_HTTP_PREFIX_CONFIG", namespace);
                    }
                    prefixes.put(
                        namespace,
                        type ->
                            plan.namespaceOf(ClassUtils.getUserClass(type))
                                .filter(namespace::equals)
                                .isPresent());
                  });
          mapping.setPathPrefixes(prefixes);
        }
        return bean;
      }

      @Override
      public Object postProcessAfterInitialization(Object bean, String name) {
        if (bean instanceof RequestMappingHandlerMapping mapping) {
          var plan = plans.getObject();
          mapping
              .getHandlerMethods()
              .forEach(
                  (route, handler) ->
                      ModuleHttpMappings.validate(
                          plan,
                          ClassUtils.getUserClass(handler.getBeanType()),
                          route.getPatternValues()));
        }
        return bean;
      }
    };
  }
}
