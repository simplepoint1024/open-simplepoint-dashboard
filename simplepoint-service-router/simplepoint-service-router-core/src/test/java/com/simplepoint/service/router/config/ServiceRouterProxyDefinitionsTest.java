package com.simplepoint.service.router.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.simplepoint.remoting.RemoteContract;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

class ServiceRouterProxyDefinitionsTest {

  @Test
  void multipleModuleConsumersShareOnePrimaryProxy() {
    var registry = new DefaultListableBeanFactory();
    ServiceRouterProxyDefinitions.register(registry, Contract.class);
    ServiceRouterProxyDefinitions.register(registry, Contract.class);

    assertEquals(1, registry.getBeanDefinitionCount());
    assertTrue(registry.getBeanDefinition(Contract.class.getName() + "#serviceRouterProxy").isPrimary());
  }

  @Test
  void reservedProxyNameCannotBeSilentlyReplacedByAnotherBean() {
    var registry = new DefaultListableBeanFactory();
    registry.registerBeanDefinition(Contract.class.getName() + "#serviceRouterProxy",
        new RootBeanDefinition(String.class));

    assertThrows(IllegalStateException.class,
        () -> ServiceRouterProxyDefinitions.register(registry, Contract.class));
  }

  @Test
  void nonContractTypesCannotBeRegisteredAsRoutedServices() {
    var registry = new DefaultListableBeanFactory();

    assertThrows(IllegalArgumentException.class,
        () -> ServiceRouterProxyDefinitions.register(registry, String.class));
    assertThrows(IllegalArgumentException.class,
        () -> ServiceRouterProxyDefinitions.register(registry, Runnable.class));
    assertEquals(0, registry.getBeanDefinitionCount());
  }

  @RemoteContract(name = "fixture")
  interface Contract {}
}
