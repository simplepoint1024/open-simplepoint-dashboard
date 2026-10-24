package com.simplepoint.service.router.invocation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.simplepoint.service.router.exception.ServiceRouterException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;

/** One precise wire representation shared by the RPC client, HTTP entry and dispatcher. */
public final class ServiceRouterJsonCodec {

  private final ObjectMapper mapper;

  /**
   * Copies application modules without mutating the application-wide mapper.
   * Untyped decimal values must not be reduced to binary floating point.
   *
   * @param applicationMapper application JSON configuration
   */
  public ServiceRouterJsonCodec(final ObjectMapper applicationMapper) {
    mapper = applicationMapper.copy()
        .registerModule(new JavaTimeModule())
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  /** Encodes an RPC envelope without depending on an HTTP client's default converters. */
  public byte[] encode(final Object value) {
    try {
      return mapper.writeValueAsBytes(value);
    } catch (JsonProcessingException ex) {
      throw new ServiceRouterException("Cannot encode service invocation envelope", ex);
    }
  }

  /** Decodes a request while retaining exact decimals in its untyped argument tree. */
  public RemoteRequest decodeRequest(final byte[] body) {
    return decode(body, RemoteRequest.class);
  }

  /** Decodes a response while retaining exact decimals in its untyped result tree. */
  public RemoteResponse decodeResponse(final byte[] body) {
    return decode(body, RemoteResponse.class);
  }

  /** Resolves inherited contract type parameters before binding operation arguments. */
  public Object[] arguments(
      final Class<?> contract, final Method method, final List<Object> values) {
    Type[] types = method.getGenericParameterTypes();
    if ((values == null ? 0 : values.size()) != types.length) {
      throw new IllegalArgumentException("Service invocation argument count does not match the contract");
    }
    Object[] result = new Object[types.length];
    for (int i = 0; i < types.length; i++) {
      result[i] = mapper.convertValue(values.get(i), memberType(contract, method, types[i]));
    }
    return result;
  }

  /** Reconstructs the declared result, including Optional and inherited generic return types. */
  public Object result(final Class<?> contract, final Method method, final Object value) {
    if (method.getReturnType() == void.class) {
      return null;
    }
    JavaType type = memberType(contract, method, method.getGenericReturnType());
    if (type.hasRawClass(Optional.class)) {
      JavaType content = type.containedTypeOrUnknown(0);
      return value == null ? Optional.empty() : Optional.of(mapper.convertValue(value, content));
    }
    return mapper.convertValue(value, type);
  }

  private JavaType memberType(final Class<?> contract, final Method method, final Type member) {
    JavaType declaringType = mapper.constructType(contract).findSuperType(method.getDeclaringClass());
    if (declaringType == null) {
      throw new IllegalArgumentException("Operation does not belong to the routed contract");
    }
    return mapper.getTypeFactory().resolveMemberType(member, declaringType.getBindings());
  }

  private <T> T decode(final byte[] body, final Class<T> type) {
    if (body == null || body.length == 0) {
      throw new ServiceRouterException("Empty service invocation envelope");
    }
    try {
      T value = mapper.readValue(body, type);
      if (value == null) {
        throw new ServiceRouterException("Null service invocation envelope");
      }
      return value;
    } catch (IOException ex) {
      throw new ServiceRouterException("Invalid service invocation envelope", ex);
    }
  }
}
