package com.simplepoint.service.router.metadata;

import com.simplepoint.service.router.annotation.RoutedMethod;
import com.simplepoint.service.router.annotation.RoutedService;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.simplepoint.remoting.RemoteContract;

/**
 * Resolves routed service metadata from Java interfaces.
 */
public final class RoutedServiceMetadataResolver {

  private RoutedServiceMetadataResolver() {
  }

  /**
   * Resolves routed service metadata.
   *
   * @param serviceInterface routed service interface
   * @return metadata if the interface is annotated with {@link RoutedService}
   */
  public static Optional<RoutedServiceMetadata> resolve(final Class<?> serviceInterface) {
    if (serviceInterface == null || !serviceInterface.isInterface()) {
      return Optional.empty();
    }
    RoutedService routedService = serviceInterface.getAnnotation(RoutedService.class);
    RemoteContract remoteContract = serviceInterface.getAnnotation(RemoteContract.class);
    if ((routedService == null || routedService.name().isBlank())
        && (remoteContract == null || remoteContract.name().isBlank())) {
      return Optional.empty();
    }
    List<RoutedMethodMetadata> methods = Arrays.stream(serviceInterface.getMethods())
        .filter(method -> method.getDeclaringClass() != Object.class)
        .sorted(Comparator.comparing(Method::getName))
        .map(RoutedServiceMetadataResolver::resolveMethod)
        .toList();
    return Optional.of(new RoutedServiceMetadata(
        serviceInterface.getName(),
        serviceName(routedService, remoteContract),
        serviceVersion(routedService, remoteContract),
        routedService == null ? 3000L : routedService.timeout(),
        routedService == null ? 0 : Math.max(0, routedService.retries()),
        routedService == null
            ? com.simplepoint.service.router.invocation.NoFallback.class.getName()
            : routedService.fallback().getName(),
        methods
    ));
  }

  /**
   * Resolves the routed method id for a Java method.
   *
   * @param method Java method
   * @return method id
   */
  public static String methodId(final Method method) {
    RoutedMethod routedMethod = method.getAnnotation(RoutedMethod.class);
    if (routedMethod != null && !routedMethod.value().isBlank()) {
      return routedMethod.value();
    }
    return method.getName();
  }

  private static RoutedMethodMetadata resolveMethod(final Method method) {
    return new RoutedMethodMetadata(
        method.getName(),
        methodId(method),
        Arrays.stream(method.getParameterTypes()).map(Class::getName).collect(Collectors.toList()),
        method.getReturnType().getName()
    );
  }

  private static String serviceName(
      final RoutedService routedService,
      final RemoteContract remoteContract
  ) {
    if (routedService != null && !routedService.name().isBlank()) {
      return routedService.name();
    }
    return remoteContract.name();
  }

  private static String serviceVersion(
      final RoutedService routedService,
      final RemoteContract remoteContract
  ) {
    if (routedService != null) {
      return routedService.version().isBlank() ? "1.0" : routedService.version();
    }
    return remoteContract.version().isBlank() ? "1" : remoteContract.version();
  }
}
