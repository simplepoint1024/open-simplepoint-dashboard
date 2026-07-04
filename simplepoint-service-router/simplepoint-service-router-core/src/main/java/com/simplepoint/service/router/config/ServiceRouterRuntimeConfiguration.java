package com.simplepoint.service.router.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.simplepoint.service.router.invocation.ClientCredentialsServiceRouterAccessTokenProvider;
import com.simplepoint.service.router.invocation.NoOpServiceRouterAccessTokenProvider;
import com.simplepoint.service.router.invocation.RemoteInvoker;
import com.simplepoint.service.router.invocation.RestClientRemoteInvoker;
import com.simplepoint.service.router.invocation.ServiceInvocationDispatcher;
import com.simplepoint.service.router.invocation.ServiceRouterAccessTokenProvider;
import com.simplepoint.service.router.loadbalance.LoadBalancer;
import com.simplepoint.service.router.loadbalance.RoundRobinLoadBalancer;
import com.simplepoint.service.router.registry.ApplicationContextLocalServiceRegistry;
import com.simplepoint.service.router.registry.CapabilityRegistry;
import com.simplepoint.service.router.registry.LocalServiceRegistry;
import com.simplepoint.service.router.routing.DiscoveryClientServiceDiscovery;
import com.simplepoint.service.router.routing.ServiceDiscovery;
import com.simplepoint.service.router.web.ServiceRouterController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Runtime beans for service routing.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ServiceRouterProperties.class)
public class ServiceRouterRuntimeConfiguration {

  /**
   * Creates the default ObjectMapper when the application has none.
   *
   * @return object mapper
   */
  @Bean
  @ConditionalOnMissingBean
  public ObjectMapper serviceRouterObjectMapper() {
    ObjectMapper objectMapper = new ObjectMapper();
    objectMapper.registerModule(new JavaTimeModule());
    return objectMapper;
  }

  /**
   * Creates the local routed service registry.
   *
   * @param applicationContext Spring application context
   * @return local provider registry
   */
  @Bean
  @ConditionalOnMissingBean
  public LocalServiceRegistry localServiceRegistry(final ApplicationContext applicationContext) {
    return new ApplicationContextLocalServiceRegistry(applicationContext);
  }

  /**
   * Creates a registry exposing local routed capabilities.
   *
   * @param localServiceRegistry local provider registry
   * @return capability registry
   */
  @Bean
  @ConditionalOnMissingBean
  public CapabilityRegistry capabilityRegistry(final LocalServiceRegistry localServiceRegistry) {
    return new CapabilityRegistry(localServiceRegistry);
  }

  /**
   * Creates the default route load balancer.
   *
   * @return load balancer
   */
  @Bean
  @ConditionalOnMissingBean
  public LoadBalancer serviceRouterLoadBalancer() {
    return new RoundRobinLoadBalancer();
  }

  /**
   * Creates service discovery.
   *
   * @param discoveryClientProvider optional Spring Cloud discovery client
   * @param properties router properties
   * @return service discovery
   */
  @Bean
  @ConditionalOnMissingBean
  public ServiceDiscovery serviceDiscovery(
      final ObjectProvider<DiscoveryClient> discoveryClientProvider,
      final ServiceRouterProperties properties
  ) {
    DiscoveryClient discoveryClient = discoveryClientProvider.getIfAvailable();
    if (discoveryClient == null) {
      return service -> java.util.List.of();
    }
    return new DiscoveryClientServiceDiscovery(discoveryClient, properties);
  }

  /**
   * Creates the default RestClient for remote invocations.
   *
   * @param properties router properties
   * @return RestClient
   */
  @Bean
  @ConditionalOnMissingBean
  public RestClient serviceRouterRestClient(final ServiceRouterProperties properties) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(properties.getHttp().getConnectTimeout());
    requestFactory.setReadTimeout(properties.getHttp().getReadTimeout());
    return RestClient.builder()
        .requestFactory(requestFactory)
        .build();
  }

  /**
   * Creates the default service access token provider.
   *
   * @param serviceRouterRestClient HTTP client
   * @param properties router properties
   * @return access token provider
   */
  @Bean
  @ConditionalOnMissingBean
  public ServiceRouterAccessTokenProvider serviceRouterAccessTokenProvider(
      final RestClient serviceRouterRestClient,
      final ServiceRouterProperties properties
  ) {
    if ("oauth2".equalsIgnoreCase(properties.getInternalAuth().getMode())) {
      return new ClientCredentialsServiceRouterAccessTokenProvider(
          serviceRouterRestClient,
          properties.getInternalAuth().getOauth2()
      );
    }
    return new NoOpServiceRouterAccessTokenProvider();
  }

  /**
   * Creates the default remote invoker.
   *
   * @param serviceRouterRestClient HTTP client
   * @param properties router properties
   * @param accessTokenProvider service access token provider
   * @return remote invoker
   */
  @Bean
  @ConditionalOnMissingBean
  public RemoteInvoker remoteInvoker(
      final RestClient serviceRouterRestClient,
      final ServiceRouterProperties properties,
      final ServiceRouterAccessTokenProvider accessTokenProvider
  ) {
    return new RestClientRemoteInvoker(serviceRouterRestClient, properties, accessTokenProvider);
  }

  /**
   * Creates the inbound request dispatcher.
   *
   * @param localServiceRegistry local provider registry
   * @param objectMapper JSON value converter
   * @return dispatcher
   */
  @Bean
  @ConditionalOnMissingBean
  public ServiceInvocationDispatcher serviceInvocationDispatcher(
      final LocalServiceRegistry localServiceRegistry,
      final ObjectMapper objectMapper
  ) {
    return new ServiceInvocationDispatcher(localServiceRegistry, objectMapper);
  }

  /**
   * Creates the HTTP endpoint for inbound routed invocations.
   *
   * @param dispatcher invocation dispatcher
   * @return service router controller
   */
  @Bean
  @ConditionalOnMissingBean
  public ServiceRouterController serviceRouterController(final ServiceInvocationDispatcher dispatcher) {
    return new ServiceRouterController(dispatcher);
  }
}
