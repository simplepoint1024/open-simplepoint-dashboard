package com.simplepoint.service.router.invocation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplepoint.service.router.exception.ServiceRouteNotFoundException;
import com.simplepoint.service.router.exception.ServiceRouterException;
import com.simplepoint.service.router.metadata.RoutedServiceMetadataResolver;
import com.simplepoint.service.router.registry.LocalRoutedService;
import com.simplepoint.service.router.registry.LocalServiceRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.simplepoint.remoting.RemoteBusinessException;

/** Dispatches inbound service router requests to local providers. */
public class ServiceInvocationDispatcher {

  private final LocalServiceRegistry localServiceRegistry;

  private final ServiceRouterJsonCodec codec;

  /**
   * Creates a dispatcher for inbound remote requests.
   *
   * @param localServiceRegistry local provider registry
   * @param objectMapper JSON value converter
   */
  public ServiceInvocationDispatcher(
      final LocalServiceRegistry localServiceRegistry, final ObjectMapper objectMapper) {
    this.localServiceRegistry = localServiceRegistry;
    this.codec = new ServiceRouterJsonCodec(objectMapper);
  }

  /**
   * Invokes a local provider from a remote request.
   *
   * @param request remote request
   * @return remote response
   */
  public RemoteResponse dispatch(final RemoteRequest request) {
    try {
      LocalRoutedService routedService =
          localServiceRegistry
              .find(request.service(), request.version())
              .orElseThrow(
                  () -> new ServiceRouteNotFoundException(request.service(), request.version()));
      return dispatch(routedService, request);
    } catch (Exception ex) {
      return RemoteResponse.failure("REMOTE_SERVICE_ERROR", "Remote service invocation failed");
    }
  }

  private RemoteResponse dispatch(
      final LocalRoutedService routedService, final RemoteRequest request) {
    try {
      if (!Objects.equals(routedService.metadata().name(), request.service())
          || !Objects.equals(routedService.metadata().version(), request.version())) {
        throw new IllegalArgumentException("Service invocation target does not match the envelope");
      }
      Method method = resolveMethod(routedService, request);
      Object[] args = codec.arguments(routedService.interfaceType(), method, request.args());
      Object result = method.invoke(routedService.bean(), args);
      return RemoteResponse.success(unwrapOptional(result));
    } catch (InvocationTargetException ex) {
      Throwable target = ex.getTargetException() == null ? ex : ex.getTargetException();
      if (target instanceof RemoteBusinessException business) {
        return RemoteResponse.failure(business.remoteErrorCode(), business.getMessage());
      }
      return RemoteResponse.failure("REMOTE_SERVICE_ERROR", "Remote service invocation failed");
    } catch (Exception ex) {
      return RemoteResponse.failure("REMOTE_SERVICE_ERROR", "Remote service invocation failed");
    }
  }

  /**
   * Dispatches the same JSON envelope used by HTTP, independent of MVC/WebFlux mapper defaults.
   *
   * @param body wire request
   * @return wire response
   */
  public byte[] dispatchJson(final byte[] body) {
    return dispatchJson(null, body);
  }

  /**
   * Uses an already resolved local provider with the same wire validation as the HTTP dispatcher. A
   * null target selects the provider from the registry.
   */
  public byte[] dispatchJson(final LocalRoutedService service, final byte[] body) {
    RemoteRequest request;
    try {
      request = codec.decodeRequest(body);
    } catch (ServiceRouterException ex) {
      return codec.encode(
          RemoteResponse.failure("INVALID_REQUEST", "Invalid service invocation envelope"));
    }
    return codec.encode(service == null ? dispatch(request) : dispatch(service, request));
  }

  private Method resolveMethod(
      final LocalRoutedService routedService, final RemoteRequest request) {
    int argCount = request.args() == null ? 0 : request.args().size();
    for (Method method : routedService.interfaceType().getMethods()) {
      if (method.getParameterCount() == argCount
          && Objects.equals(RoutedServiceMetadataResolver.methodId(method), request.method())) {
        return method;
      }
    }
    String expectedCounts =
        Arrays.stream(routedService.interfaceType().getMethods())
            .filter(
                method ->
                    Objects.equals(
                        RoutedServiceMetadataResolver.methodId(method), request.method()))
            .map(Method::getParameterCount)
            .distinct()
            .sorted()
            .map(String::valueOf)
            .collect(Collectors.joining(","));
    throw new IllegalArgumentException(
        "No routed method found: "
            + request.service()
            + "#"
            + request.method()
            + " with "
            + argCount
            + " arguments; expected counts: ["
            + expectedCounts
            + "]");
  }

  private Object unwrapOptional(final Object value) {
    if (value instanceof Optional<?> optional) {
      return optional.orElse(null);
    }
    return value;
  }
}
