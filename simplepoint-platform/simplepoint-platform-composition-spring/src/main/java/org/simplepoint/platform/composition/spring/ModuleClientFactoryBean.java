package org.simplepoint.platform.composition.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplepoint.service.router.config.ServiceRouterProperties;
import com.simplepoint.service.router.config.ServiceRouterProxyDefinitions;
import com.simplepoint.service.router.invocation.RemoteInvoker;
import com.simplepoint.service.router.loadbalance.LoadBalancer;
import com.simplepoint.service.router.metadata.RoutedServiceMetadataResolver;
import com.simplepoint.service.router.proxy.ServiceRouterInvocationHandler;
import com.simplepoint.service.router.registry.LocalServiceRegistry;
import com.simplepoint.service.router.routing.ServiceDiscovery;
import java.lang.reflect.Proxy;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.platform.composition.ModulePlan;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ApplicationContext;

/**
 * A prototype client factory uses Spring's requesting injection point to bind an immutable module
 * identity. Business components keep ordinary constructor, field and ObjectProvider injection.
 */
public final class ModuleClientFactoryBean implements FactoryBean<Object> {
  private final Class<?> contract;
  private final String module;
  private final ModuleServiceCredentials credentials;
  private final ApplicationContext context;

  /**
   * Resolves ownership from trusted module metadata, never from an HTTP header or method argument.
   */
  public ModuleClientFactoryBean(
      Class<?> contract,
      InjectionPoint injectionPoint,
      ModulePlan plan,
      ModuleServiceCredentials credentials,
      ApplicationContext context) {
    this.contract = contract;
    this.credentials = credentials;
    this.context = context;
    Class<?> owner =
        injectionPoint.getMethodParameter() == null
            ? injectionPoint.getMember().getDeclaringClass()
            : injectionPoint.getMethodParameter().getContainingClass();
    var descriptor =
        plan.ownerOf(owner)
            .filter(
                candidate -> owner.getClassLoader() == candidate.configuration().getClassLoader())
            .orElseThrow(
                () -> new ModuleCompositionException("UNOWNED_MODULE_CLIENT", owner.getName()));
    var metadata = RoutedServiceMetadataResolver.resolve(contract).orElseThrow();
    String capability = metadata.name() + ":" + metadata.version();
    if (descriptor.consumes().get(capability) != contract) {
      throw new ModuleCompositionException(
          "UNDECLARED_MODULE_CONSUMER", descriptor.id() + " -> " + capability);
    }
    this.module = descriptor.id();
  }

  @Override
  public Object getObject() {
    return boundClient(contract, module, credentials, context);
  }

  // Only assembly infrastructure can create a client without an injection point. The caller is
  // captured from the selected module declaration, never supplied by an application request.
  static Object boundClient(
      Class<?> contract,
      String module,
      ModuleServiceCredentials credentials,
      ApplicationContext context) {
    var metadata = RoutedServiceMetadataResolver.resolve(contract).orElseThrow();
    var handler =
        new ServiceRouterInvocationHandler(
            contract,
            metadata,
            context.getBean(LocalServiceRegistry.class),
            context.getBean(ServiceDiscovery.class),
            context.getBean(LoadBalancer.class),
            context.getBean(RemoteInvoker.class),
            context.getBean(ObjectMapper.class),
            context.getBean(ServiceRouterProperties.class),
            context);
    return Proxy.newProxyInstance(
        contract.getClassLoader(),
        new Class<?>[] {contract},
        (proxy, method, args) -> {
          if (method.getDeclaringClass() == Object.class) {
            return handler.invoke(proxy, method, args);
          }
          try (var ignored = credentials.bind(module)) {
            return handler.invoke(proxy, method, args);
          }
        });
  }

  @Override
  public Class<?> getObjectType() {
    return contract;
  }

  @Override
  public boolean isSingleton() {
    return false;
  }

  /**
   * Registers one prototype definition per contract; each injection keeps its own module binding.
   */
  static void register(BeanDefinitionRegistry registry, Class<?> contract) {
    String name = contract.getName() + "#serviceRouterProxy";
    if (registry.containsBeanDefinition(name)) {
      var existing = registry.getBeanDefinition(name);
      if (!ModuleClientFactoryBean.class.getName().equals(existing.getBeanClassName())
          || existing.getAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE) != contract
          || !existing.isPrototype()
          || !existing.isPrimary()
          || !Boolean.TRUE.equals(
              existing.getAttribute(ServiceRouterProxyDefinitions.CLIENT_PROXY_ATTRIBUTE))) {
        throw new ModuleCompositionException("CONFLICTING_MODULE_CLIENT", name);
      }
      return;
    }
    var definition =
        BeanDefinitionBuilder.genericBeanDefinition(ModuleClientFactoryBean.class)
            .addConstructorArgValue(contract)
            .setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR)
            .setScope(BeanDefinition.SCOPE_PROTOTYPE)
            .setPrimary(true)
            .getBeanDefinition();
    definition.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE, contract);
    definition.setAttribute(ServiceRouterProxyDefinitions.CLIENT_PROXY_ATTRIBUTE, true);
    registry.registerBeanDefinition(name, definition);
  }
}
