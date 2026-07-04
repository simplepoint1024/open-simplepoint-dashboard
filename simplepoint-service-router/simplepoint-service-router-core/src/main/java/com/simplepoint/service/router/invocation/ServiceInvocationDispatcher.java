package com.simplepoint.service.router.invocation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplepoint.service.router.exception.ServiceRouteNotFoundException;
import com.simplepoint.service.router.metadata.RoutedServiceMetadataResolver;
import com.simplepoint.service.router.registry.LocalRoutedService;
import com.simplepoint.service.router.registry.LocalServiceRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Objects;

/**
 * Dispatches inbound service router requests to local providers.
 */
public class ServiceInvocationDispatcher {

  private final LocalServiceRegistry localServiceRegistry;

  private final ObjectMapper objectMapper;

  /**
   * Creates a dispatcher for inbound remote requests.
   *
   * @param localServiceRegistry local provider registry
   * @param objectMapper JSON value converter
   */
  public ServiceInvocationDispatcher(
      final LocalServiceRegistry localServiceRegistry,
      final ObjectMapper objectMapper
  ) {
    this.localServiceRegistry = localServiceRegistry;
    this.objectMapper = objectMapper;
  }

  /**
   * Invokes a local provider from a remote request.
   *
   * @param request remote request
   * @return remote response
   */
  public RemoteResponse dispatch(final RemoteRequest request) {
    try {
      LocalRoutedService routedService = localServiceRegistry.find(request.service(), request.version())
          .orElseThrow(() -> new ServiceRouteNotFoundException(request.service(), request.version()));
      Method method = resolveMethod(routedService, request);
      Object[] args = convertArgs(request.args(), method.getGenericParameterTypes());
      Object result = method.invoke(routedService.bean(), args);
      return RemoteResponse.success(result);
    } catch (InvocationTargetException ex) {
      Throwable target = ex.getTargetException() == null ? ex : ex.getTargetException();
      return RemoteResponse.failure("REMOTE_SERVICE_ERROR", target.getMessage());
    } catch (Exception ex) {
      return RemoteResponse.failure("REMOTE_SERVICE_ERROR", ex.getMessage());
    }
  }

  private Method resolveMethod(final LocalRoutedService routedService, final RemoteRequest request) {
    int argCount = request.args() == null ? 0 : request.args().size();
    for (Method method : routedService.interfaceType().getMethods()) {
      if (method.getParameterCount() == argCount
          && Objects.equals(RoutedServiceMetadataResolver.methodId(method), request.method())) {
        return method;
      }
    }
    throw new IllegalArgumentException("No routed method found: " + request.service() + "#" + request.method());
  }

  private Object[] convertArgs(final List<Object> args, final Type[] parameterTypes) {
    Object[] result = new Object[parameterTypes.length];
    for (int i = 0; i < parameterTypes.length; i++) {
      Object value = args == null ? null : args.get(i);
      result[i] = objectMapper.convertValue(
          value,
          objectMapper.getTypeFactory().constructType(parameterTypes[i])
      );
    }
    return result;
  }
}
