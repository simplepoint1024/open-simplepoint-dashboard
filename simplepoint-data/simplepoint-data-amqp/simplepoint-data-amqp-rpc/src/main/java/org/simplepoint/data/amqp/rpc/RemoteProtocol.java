/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.data.amqp.rpc;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.amqp.core.MessageBuilderSupport;
import org.springframework.amqp.core.MessageProperties;

/**
 * Internal AMQP RPC protocol helpers.
 */
final class RemoteProtocol {

  static final String HEADER_INTERFACE_NAME = "sp-interface";

  static final String HEADER_METHOD_NAME = "sp-method";

  static final String HEADER_PARAMETER_TYPES = "sp-parameter-types";

  static final String HEADER_REMOTE_ERROR = "sp-remote-error";

  static final String HEADER_PROTOCOL_VERSION = "sp-protocol-version";

  static final String PROTOCOL_VERSION = "1";

  private RemoteProtocol() {
  }

  static Object[] normalizeArguments(final Object[] args) {
    return args == null ? new Object[0] : args;
  }

  static String[] parameterTypeNames(final Method method) {
    return parameterTypeNames(method.getParameterTypes());
  }

  static String[] parameterTypeNames(final Class<?>[] parameterTypes) {
    String[] names = new String[parameterTypes.length];
    for (int i = 0; i < parameterTypes.length; i++) {
      names[i] = parameterTypes[i].getName();
    }
    return names;
  }

  static String encodeParameterTypes(final String[] parameterTypes) {
    return parameterTypes == null || parameterTypes.length == 0 ? "" : String.join(",", parameterTypes);
  }

  static String[] decodeParameterTypes(final Object headerValue) {
    if (headerValue == null) {
      return new String[0];
    }
    if (headerValue instanceof String value) {
      if (value.isBlank()) {
        return new String[0];
      }
      return value.split(",");
    }
    if (headerValue instanceof String[] values) {
      return values;
    }
    if (headerValue instanceof Collection<?> values) {
      List<String> resolved = new ArrayList<>();
      for (Object value : values) {
        if (value != null) {
          resolved.add(value.toString());
        }
      }
      return resolved.toArray(String[]::new);
    }
    return new String[0];
  }

  static String signature(final Method method) {
    return signature(method.getDeclaringClass().getName(), method.getName(), parameterTypeNames(method));
  }

  static String signature(final String interfaceName, final String methodName, final String[] parameterTypes) {
    return interfaceName + "." + methodName + "(" + encodeParameterTypes(parameterTypes) + ")";
  }

  static <T> MessageBuilderSupport<T> withProtocolVersion(final MessageBuilderSupport<T> builder) {
    return builder.setHeader(HEADER_PROTOCOL_VERSION, PROTOCOL_VERSION);
  }

  static String resolveProtocolVersion(final MessageProperties properties) {
    String protocolVersion = headerAsString(properties, HEADER_PROTOCOL_VERSION);
    return protocolVersion == null || protocolVersion.isBlank() ? PROTOCOL_VERSION : protocolVersion;
  }

  static boolean isSupportedProtocolVersion(final String protocolVersion) {
    return PROTOCOL_VERSION.equals(protocolVersion);
  }

  static void assertSupportedProtocolVersion(final MessageProperties properties) {
    String protocolVersion = resolveProtocolVersion(properties);
    if (!isSupportedProtocolVersion(protocolVersion)) {
      throw unsupportedProtocolVersion(protocolVersion);
    }
  }

  static IllegalStateException unsupportedProtocolVersion(final String protocolVersion) {
    return new IllegalStateException(
        "Unsupported AMQP RPC protocol version ["
            + protocolVersion
            + "], supported ["
            + PROTOCOL_VERSION
            + "]"
    );
  }

  static boolean isRemoteError(final MessageProperties properties) {
    if (properties == null) {
      return false;
    }
    Object value = properties.getHeaders().get(HEADER_REMOTE_ERROR);
    if (value instanceof Boolean flag) {
      return flag;
    }
    return value != null && Boolean.parseBoolean(value.toString());
  }

  static RemoteMethodDescriptor resolveDescriptor(final MessageProperties properties) {
    String interfaceName = headerAsString(properties, HEADER_INTERFACE_NAME);
    String methodName = headerAsString(properties, HEADER_METHOD_NAME);
    String[] parameterTypes = decodeParameterTypes(properties == null ? null
        : properties.getHeaders().get(HEADER_PARAMETER_TYPES));

    if ((interfaceName == null || methodName == null) && properties != null && properties.getType() != null) {
      RemoteMethodDescriptor descriptor = parseType(properties.getType());
      if (interfaceName == null) {
        interfaceName = descriptor.interfaceName();
      }
      if (methodName == null) {
        methodName = descriptor.methodName();
      }
      if (parameterTypes.length == 0) {
        parameterTypes = descriptor.parameterTypes();
      }
    }
    return new RemoteMethodDescriptor(interfaceName, methodName, parameterTypes);
  }

  private static String headerAsString(final MessageProperties properties, final String headerName) {
    if (properties == null) {
      return null;
    }
    Object value = properties.getHeaders().get(headerName);
    return value == null ? null : value.toString();
  }

  static RemoteMethodDescriptor parseType(final String type) {
    if (type == null || type.isBlank()) {
      return new RemoteMethodDescriptor(null, null, new String[0]);
    }
    int opening = type.indexOf('(');
    int closing = type.lastIndexOf(')');
    String qualifiedMethod = opening > -1 ? type.substring(0, opening) : type;
    int separator = qualifiedMethod.lastIndexOf('.');
    if (separator < 0) {
      return new RemoteMethodDescriptor(null, qualifiedMethod, new String[0]);
    }
    String interfaceName = qualifiedMethod.substring(0, separator);
    String methodName = qualifiedMethod.substring(separator + 1);
    if (opening < 0 || closing < opening + 1) {
      return new RemoteMethodDescriptor(interfaceName, methodName, new String[0]);
    }
    String encoded = type.substring(opening + 1, closing);
    return new RemoteMethodDescriptor(interfaceName, methodName, decodeParameterTypes(encoded));
  }

  static Class<?> wrapPrimitive(final Class<?> type) {
    if (!type.isPrimitive()) {
      return type;
    }
    return switch (type.getName()) {
      case "boolean" -> Boolean.class;
      case "byte" -> Byte.class;
      case "char" -> Character.class;
      case "double" -> Double.class;
      case "float" -> Float.class;
      case "int" -> Integer.class;
      case "long" -> Long.class;
      case "short" -> Short.class;
      default -> type;
    };
  }

  record RemoteMethodDescriptor(String interfaceName, String methodName, String[] parameterTypes) {
  }
}
