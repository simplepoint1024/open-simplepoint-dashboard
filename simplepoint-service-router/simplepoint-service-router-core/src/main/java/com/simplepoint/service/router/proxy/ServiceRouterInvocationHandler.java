package com.simplepoint.service.router.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplepoint.service.router.config.ServiceRouterProperties;
import com.simplepoint.service.router.exception.ServiceRouteNotFoundException;
import com.simplepoint.service.router.exception.ServiceRouterException;
import com.simplepoint.service.router.invocation.NoFallback;
import com.simplepoint.service.router.invocation.RemoteInvoker;
import com.simplepoint.service.router.invocation.RemoteRequest;
import com.simplepoint.service.router.invocation.RemoteResponse;
import com.simplepoint.service.router.loadbalance.LoadBalancer;
import com.simplepoint.service.router.metadata.RoutedServiceMetadata;
import com.simplepoint.service.router.metadata.RoutedServiceMetadataResolver;
import com.simplepoint.service.router.registry.LocalRoutedService;
import com.simplepoint.service.router.registry.LocalServiceRegistry;
import com.simplepoint.service.router.routing.ServiceDiscovery;
import com.simplepoint.service.router.routing.ServiceRoute;
import com.simplepoint.service.router.tracing.TraceContext;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.context.ApplicationContext;

/**
 * Invocation handler for routed service consumer proxies.
 */
public class ServiceRouterInvocationHandler implements InvocationHandler {

  private final Class<?> serviceInterface;

  private final RoutedServiceMetadata metadata;

  private final LocalServiceRegistry localServiceRegistry;

  private final ServiceDiscovery serviceDiscovery;

  private final LoadBalancer loadBalancer;

  private final RemoteInvoker remoteInvoker;

  private final ObjectMapper objectMapper;

  private final ServiceRouterProperties properties;

  private final ApplicationContext applicationContext;

  /**
   * Creates an invocation handler for a routed service proxy.
   *
   * @param serviceInterface routed interface
   * @param metadata routed service metadata
   * @param localServiceRegistry local provider registry
   * @param serviceDiscovery remote service discovery
   * @param loadBalancer route load balancer
   * @param remoteInvoker remote invoker
   * @param objectMapper JSON value converter
   * @param properties router properties
   * @param applicationContext Spring application context
   */
  public ServiceRouterInvocationHandler(
      final Class<?> serviceInterface,
      final RoutedServiceMetadata metadata,
      final LocalServiceRegistry localServiceRegistry,
      final ServiceDiscovery serviceDiscovery,
      final LoadBalancer loadBalancer,
      final RemoteInvoker remoteInvoker,
      final ObjectMapper objectMapper,
      final ServiceRouterProperties properties,
      final ApplicationContext applicationContext
  ) {
    this.serviceInterface = serviceInterface;
    this.metadata = metadata;
    this.localServiceRegistry = localServiceRegistry;
    this.serviceDiscovery = serviceDiscovery;
    this.loadBalancer = loadBalancer;
    this.remoteInvoker = remoteInvoker;
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.applicationContext = applicationContext;
  }

  @Override
  public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
    if (method.getDeclaringClass() == Object.class) {
      return invokeObjectMethod(proxy, method, args);
    }
    try {
      if (properties.getConsumer().isLocalFirst()) {
        LocalRoutedService localService = localServiceRegistry.find(metadata).orElse(null);
        if (localService != null) {
          return invokeLocal(localService, method, args);
        }
      }
      return invokeRemote(method, args);
    } catch (Exception ex) {
      Object fallback = fallbackBean();
      if (fallback != null) {
        return invokeFallback(fallback, method, args);
      }
      throw ex;
    }
  }

  private Object invokeRemote(final Method method, final Object[] args) {
    int attempts = metadata.retries() + 1;
    RuntimeException last = null;
    for (int attempt = 0; attempt < attempts; attempt++) {
      try {
        ServiceRoute route = loadBalancer.choose(serviceDiscovery.discover(metadata))
            .orElseThrow(() -> new ServiceRouteNotFoundException(metadata.name(), metadata.version()));
        RemoteRequest request = new RemoteRequest(
            metadata.name(),
            metadata.version(),
            RoutedServiceMetadataResolver.methodId(method),
            args == null ? List.of() : Arrays.asList(args),
            TraceContext.newTraceId()
        );
        RemoteResponse response = remoteInvoker.invoke(route, request);
        if (response == null) {
          throw new ServiceRouterException("Remote provider returned empty response");
        }
        if (!response.success()) {
          throw new ServiceRouterException(response.errorCode() + ": " + response.message());
        }
        if (method.getReturnType() == void.class) {
          return null;
        }
        return convertResponseData(response.data(), method);
      } catch (RuntimeException ex) {
        last = ex;
      }
    }
    throw last == null ? new ServiceRouterException("Remote invocation failed") : last;
  }

  private Object invokeLocal(final LocalRoutedService localService, final Method method, final Object[] args)
      throws Throwable {
    try {
      return method.invoke(localService.bean(), args);
    } catch (InvocationTargetException ex) {
      throw ex.getTargetException() == null ? ex : ex.getTargetException();
    }
  }

  private Object fallbackBean() {
    if (NoFallback.class.getName().equals(metadata.fallbackClassName())) {
      return null;
    }
    try {
      return applicationContext.getBean(Class.forName(metadata.fallbackClassName()));
    } catch (Exception ex) {
      return null;
    }
  }

  private Object invokeFallback(final Object fallback, final Method method, final Object[] args) throws Throwable {
    try {
      Method fallbackMethod = fallback.getClass().getMethod(method.getName(), method.getParameterTypes());
      return fallbackMethod.invoke(fallback, args);
    } catch (InvocationTargetException ex) {
      throw ex.getTargetException() == null ? ex : ex.getTargetException();
    }
  }

  private Object invokeObjectMethod(final Object proxy, final Method method, final Object[] args) {
    return switch (method.getName()) {
      case "toString" -> "ServiceRouterProxy[" + serviceInterface.getName() + "]";
      case "hashCode" -> System.identityHashCode(proxy);
      case "equals" -> proxy == args[0];
      default -> throw new IllegalStateException("Unsupported Object method: " + method.getName());
    };
  }

  private Object convertResponseData(final Object data, final Method method) {
    if (method.getReturnType() == Optional.class) {
      if (data == null) {
        return Optional.empty();
      }
      return Optional.of(convertOptionalValue(data, method.getGenericReturnType()));
    }
    return objectMapper.convertValue(
        data,
        objectMapper.getTypeFactory().constructType(method.getGenericReturnType())
    );
  }

  private Object convertOptionalValue(final Object data, final Type returnType) {
    if (returnType instanceof ParameterizedType parameterizedType
        && parameterizedType.getActualTypeArguments().length > 0) {
      return objectMapper.convertValue(
          data,
          objectMapper.getTypeFactory().constructType(parameterizedType.getActualTypeArguments()[0])
      );
    }
    return data;
  }
}
