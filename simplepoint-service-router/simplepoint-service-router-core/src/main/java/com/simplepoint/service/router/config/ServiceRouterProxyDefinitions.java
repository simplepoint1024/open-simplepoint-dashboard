package com.simplepoint.service.router.config;

import com.simplepoint.service.router.metadata.RoutedServiceMetadataResolver;
import com.simplepoint.service.router.proxy.ServiceRouterProxyFactoryBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;

/** Shared proxy registration for package discovery and explicit module contracts. */
public final class ServiceRouterProxyDefinitions {

  /** Marks infrastructure client definitions so provider discovery never advertises a client. */
  public static final String CLIENT_PROXY_ATTRIBUTE =
      ServiceRouterProxyDefinitions.class.getName() + ".clientProxy";

  private ServiceRouterProxyDefinitions() {}

  /**
   * Registers one primary routing boundary for a contract, including local implementations.
   *
   * @param registry target application registry
   * @param serviceInterface validated routed interface
   */
  public static void register(
      final BeanDefinitionRegistry registry, final Class<?> serviceInterface) {
    if (!serviceInterface.isInterface()
        || RoutedServiceMetadataResolver.resolve(serviceInterface).isEmpty()) {
      throw new IllegalArgumentException("Not a routed contract: " + serviceInterface.getName());
    }
    String beanName = serviceInterface.getName() + "#serviceRouterProxy";
    if (registry.containsBeanDefinition(beanName)) {
      BeanDefinition existing = registry.getBeanDefinition(beanName);
      if (!ServiceRouterProxyFactoryBean.class.getName().equals(existing.getBeanClassName())
          || existing.getAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE) != serviceInterface
          || !existing.isPrimary()) {
        throw new IllegalStateException("Conflicting routing proxy definition: " + beanName);
      }
      return;
    }
    BeanDefinitionBuilder builder =
        BeanDefinitionBuilder.genericBeanDefinition(ServiceRouterProxyFactoryBean.class)
            .addConstructorArgValue(serviceInterface)
            .setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR)
            .setPrimary(true)
            .setLazyInit(true);
    BeanDefinition beanDefinition = builder.getBeanDefinition();
    beanDefinition.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE, serviceInterface);
    beanDefinition.setAttribute(CLIENT_PROXY_ATTRIBUTE, true);
    registry.registerBeanDefinition(beanName, beanDefinition);
  }
}
