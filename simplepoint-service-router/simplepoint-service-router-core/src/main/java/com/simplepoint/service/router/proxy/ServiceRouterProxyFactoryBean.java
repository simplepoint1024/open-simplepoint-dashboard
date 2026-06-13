package com.simplepoint.service.router.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplepoint.service.router.config.ServiceRouterProperties;
import com.simplepoint.service.router.invocation.RemoteInvoker;
import com.simplepoint.service.router.loadbalance.LoadBalancer;
import com.simplepoint.service.router.metadata.RoutedServiceMetadata;
import com.simplepoint.service.router.metadata.RoutedServiceMetadataResolver;
import com.simplepoint.service.router.registry.LocalServiceRegistry;
import com.simplepoint.service.router.routing.ServiceDiscovery;
import java.lang.reflect.Proxy;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.ApplicationContext;

/**
 * FactoryBean creating routed service consumer proxies.
 */
public class ServiceRouterProxyFactoryBean implements FactoryBean<Object> {

  private final Class<?> serviceInterface;

  private final LocalServiceRegistry localServiceRegistry;

  private final ServiceDiscovery serviceDiscovery;

  private final LoadBalancer loadBalancer;

  private final RemoteInvoker remoteInvoker;

  private final ObjectMapper objectMapper;

  private final ServiceRouterProperties properties;

  private final ApplicationContext applicationContext;

  /**
   * Creates a proxy factory for a routed service interface.
   *
   * @param serviceInterface routed interface
   * @param localServiceRegistry local provider registry
   * @param serviceDiscovery remote service discovery
   * @param loadBalancer route load balancer
   * @param remoteInvoker remote invoker
   * @param objectMapper JSON value converter
   * @param properties router properties
   * @param applicationContext Spring application context
   */
  public ServiceRouterProxyFactoryBean(
      final Class<?> serviceInterface,
      final LocalServiceRegistry localServiceRegistry,
      final ServiceDiscovery serviceDiscovery,
      final LoadBalancer loadBalancer,
      final RemoteInvoker remoteInvoker,
      final ObjectMapper objectMapper,
      final ServiceRouterProperties properties,
      final ApplicationContext applicationContext
  ) {
    this.serviceInterface = serviceInterface;
    this.localServiceRegistry = localServiceRegistry;
    this.serviceDiscovery = serviceDiscovery;
    this.loadBalancer = loadBalancer;
    this.remoteInvoker = remoteInvoker;
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.applicationContext = applicationContext;
  }

  @Override
  public Object getObject() {
    RoutedServiceMetadata metadata = RoutedServiceMetadataResolver.resolve(serviceInterface)
        .orElseThrow(() -> new IllegalArgumentException("Not a routed service interface: " + serviceInterface));
    ServiceRouterInvocationHandler handler = new ServiceRouterInvocationHandler(
        serviceInterface,
        metadata,
        localServiceRegistry,
        serviceDiscovery,
        loadBalancer,
        remoteInvoker,
        objectMapper,
        properties,
        applicationContext
    );
    return Proxy.newProxyInstance(serviceInterface.getClassLoader(), new Class<?>[] {serviceInterface}, handler);
  }

  @Override
  public Class<?> getObjectType() {
    return serviceInterface;
  }
}
