package com.simplepoint.service.router.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplepoint.service.router.config.ServiceRouterProperties;
import com.simplepoint.service.router.exception.RemoteServiceFailureException;
import com.simplepoint.service.router.exception.ServiceRouteNotFoundException;
import com.simplepoint.service.router.exception.ServiceRouterException;
import com.simplepoint.service.router.invocation.IsolatedLocalInvoker;
import com.simplepoint.service.router.invocation.NoFallback;
import com.simplepoint.service.router.invocation.RemoteInvoker;
import com.simplepoint.service.router.invocation.RemoteRequest;
import com.simplepoint.service.router.invocation.RemoteResponse;
import com.simplepoint.service.router.invocation.ServiceRouterJsonCodec;
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
import java.util.Arrays;
import java.util.List;
import org.simplepoint.remoting.NoRemoteErrorDecoder;
import org.simplepoint.remoting.RemoteBusinessException;
import org.simplepoint.remoting.RemoteContract;
import org.simplepoint.remoting.RemoteErrorDecoder;
import org.simplepoint.remoting.RemoteOperation;
import org.springframework.context.ApplicationContext;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/** Invocation handler for routed service consumer proxies. */
public class ServiceRouterInvocationHandler implements InvocationHandler {

  private final Class<?> serviceInterface;

  private final RemoteErrorDecoder errorDecoder;

  private final RoutedServiceMetadata metadata;

  private final LocalServiceRegistry localServiceRegistry;

  private final ServiceDiscovery serviceDiscovery;

  private final LoadBalancer loadBalancer;

  private final RemoteInvoker remoteInvoker;

  private final ServiceRouterJsonCodec codec;

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
      final ApplicationContext applicationContext) {
    this.serviceInterface = serviceInterface;
    this.errorDecoder = createErrorDecoder(serviceInterface);
    this.metadata = metadata;
    this.localServiceRegistry = localServiceRegistry;
    this.serviceDiscovery = serviceDiscovery;
    this.loadBalancer = loadBalancer;
    this.remoteInvoker = remoteInvoker;
    this.codec = new ServiceRouterJsonCodec(objectMapper);
    this.properties = properties;
    this.applicationContext = applicationContext;
  }

  @Override
  public Object invoke(final Object proxy, final Method method, final Object[] args)
      throws Throwable {
    if (method.getDeclaringClass() == Object.class) {
      return invokeObjectMethod(proxy, method, args);
    }
    if (properties.getConsumer().isLocalFirst()) {
      LocalRoutedService localService = localServiceRegistry.find(metadata).orElse(null);
      if (localService != null) {
        return invokeLocal(localService, method, args);
      }
    }
    return invokeRemote(method, args);
  }

  private static RemoteErrorDecoder createErrorDecoder(final Class<?> serviceInterface) {
    RemoteContract contract = serviceInterface.getAnnotation(RemoteContract.class);
    if (contract == null) {
      return new NoRemoteErrorDecoder();
    }
    try {
      return contract.errorDecoder().getConstructor().newInstance();
    } catch (ReflectiveOperationException ex) {
      throw new IllegalArgumentException(
          "Cannot initialize contract error decoder: " + serviceInterface.getName(), ex);
    }
  }

  private Object invokeRemote(final Method method, final Object[] args) throws Throwable {
    RemoteOperation operation = method.getAnnotation(RemoteOperation.class);
    boolean idempotent = operation != null && operation.idempotent();
    int attempts = idempotent ? metadata.retries() + 1 : 1;
    RemoteRequest request =
        new RemoteRequest(
            metadata.name(),
            metadata.version(),
            RoutedServiceMetadataResolver.methodId(method),
            args == null ? List.of() : Arrays.asList(args),
            TraceContext.newTraceId());
    for (int attempt = 0; attempt < attempts; attempt++) {
      RemoteResponse response;
      try {
        ServiceRoute route =
            loadBalancer
                .choose(serviceDiscovery.discover(metadata))
                .orElseThrow(
                    () -> new ServiceRouteNotFoundException(metadata.name(), metadata.version()));
        response = remoteInvoker.invoke(route, request);
      } catch (ResourceAccessException
          | HttpServerErrorException
          | ServiceRouteNotFoundException ex) {
        // Only explicitly idempotent operations may repeat an uncertain delivery.
        // Provider/authentication/decoding failures never enter this recovery path.
        if (idempotent) {
          if (attempt + 1 < attempts) {
            continue;
          }
          Object fallback = fallbackBean();
          if (fallback != null) {
            return invokeFallback(fallback, method, args);
          }
        }
        throw ex;
      }
      return result(method, response);
    }
    throw new ServiceRouterException("Remote invocation has no configured attempts");
  }

  private Object invokeLocal(
      final LocalRoutedService localService, final Method method, final Object[] args)
      throws Throwable {
    RemoteContract contract = serviceInterface.getAnnotation(RemoteContract.class);
    RemoteContract providerContract =
        localService.interfaceType().getAnnotation(RemoteContract.class);
    if (properties.getConsumer().isLocalIsolation()
        || (contract != null && contract.isolateLocalCalls())
        || (providerContract != null && providerContract.isolateLocalCalls())) {
      RemoteRequest request =
          new RemoteRequest(
              metadata.name(),
              metadata.version(),
              RoutedServiceMetadataResolver.methodId(method),
              args == null ? List.of() : Arrays.asList(args),
              TraceContext.newTraceId());
      return result(
          method,
          applicationContext.getBean(IsolatedLocalInvoker.class).invoke(localService, request));
    }
    try {
      return method.invoke(localService.bean(), args);
    } catch (InvocationTargetException ex) {
      throw ex.getTargetException() == null ? ex : ex.getTargetException();
    }
  }

  private Object result(final Method method, final RemoteResponse response) {
    if (response == null) {
      throw new ServiceRouterException("Remote provider returned empty response");
    }
    if (!response.success()) {
      RemoteBusinessException business =
          errorDecoder.decode(response.errorCode(), response.message());
      if (business != null) {
        throw business;
      }
      throw new RemoteServiceFailureException(response.errorCode(), response.message());
    }
    return codec.result(serviceInterface, method, response.data());
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

  private Object invokeFallback(final Object fallback, final Method method, final Object[] args)
      throws Throwable {
    try {
      Method fallbackMethod =
          fallback.getClass().getMethod(method.getName(), method.getParameterTypes());
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
}
