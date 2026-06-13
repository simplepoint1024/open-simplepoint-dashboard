package com.simplepoint.service.router.registry;

import com.simplepoint.service.router.metadata.RoutedServiceMetadata;
import com.simplepoint.service.router.metadata.RoutedServiceMetadataResolver;
import com.simplepoint.service.router.proxy.ServiceRouterProxyFactoryBean;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Discovers local routed service providers from the Spring application context.
 */
public class ApplicationContextLocalServiceRegistry implements LocalServiceRegistry, InitializingBean {

  private final ApplicationContext applicationContext;

  private final Map<String, LocalRoutedService> providers = new LinkedHashMap<>();

  /**
   * Creates a local provider registry backed by the Spring application context.
   *
   * @param applicationContext Spring application context
   */
  public ApplicationContextLocalServiceRegistry(final ApplicationContext applicationContext) {
    this.applicationContext = applicationContext;
  }

  @Override
  public void afterPropertiesSet() {
    if (!(applicationContext instanceof ConfigurableApplicationContext configurableApplicationContext)) {
      return;
    }
    ConfigurableListableBeanFactory beanFactory = configurableApplicationContext.getBeanFactory();
    for (String beanName : beanFactory.getBeanDefinitionNames()) {
      if (isServiceRouterProxyBean(beanFactory, beanName)) {
        continue;
      }
      Class<?> beanType = beanFactory.getType(beanName, false);
      if (beanType != null && !ServiceRouterProxyFactoryBean.class.isAssignableFrom(beanType)) {
        registerBean(beanName, beanType);
      }
    }
  }

  @Override
  public Optional<LocalRoutedService> find(final String service, final String version) {
    return Optional.ofNullable(providers.get(key(service, version)));
  }

  @Override
  public Collection<LocalRoutedService> all() {
    return providers.values();
  }

  private void registerBean(final String beanName, final Class<?> beanType) {
    LinkedHashSet<Class<?>> routedInterfaces = findRoutedInterfaces(beanType);
    if (routedInterfaces.isEmpty()) {
      return;
    }
    for (Class<?> interfaceType : routedInterfaces) {
      Optional<RoutedServiceMetadata> metadata = RoutedServiceMetadataResolver.resolve(interfaceType);
      metadata.ifPresent(value -> providers.putIfAbsent(
          key(value.name(), value.version()),
          new LocalRoutedService(value, () -> applicationContext.getBean(beanName), interfaceType)
      ));
    }
  }

  private static boolean isServiceRouterProxyBean(
      final ConfigurableListableBeanFactory beanFactory,
      final String beanName
  ) {
    if (!beanFactory.containsBeanDefinition(beanName)) {
      return false;
    }
    String beanClassName = beanFactory.getBeanDefinition(beanName).getBeanClassName();
    return ServiceRouterProxyFactoryBean.class.getName().equals(beanClassName);
  }

  private static LinkedHashSet<Class<?>> findRoutedInterfaces(final Class<?> beanType) {
    LinkedHashSet<Class<?>> interfaces = new LinkedHashSet<>();
    Class<?> current = beanType;
    while (current != null && current != Object.class) {
      for (Class<?> interfaceType : current.getInterfaces()) {
        collectRoutedInterfaces(interfaceType, interfaces);
      }
      current = current.getSuperclass();
    }
    return interfaces;
  }

  private static void collectRoutedInterfaces(
      final Class<?> interfaceType,
      final LinkedHashSet<Class<?>> interfaces
  ) {
    if (RoutedServiceMetadataResolver.resolve(interfaceType).isPresent()) {
      interfaces.add(interfaceType);
    }
    for (Class<?> parentInterface : interfaceType.getInterfaces()) {
      collectRoutedInterfaces(parentInterface, interfaces);
    }
  }

  private static String key(final String service, final String version) {
    return service + ":" + version;
  }
}
